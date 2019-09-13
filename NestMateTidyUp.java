import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

// java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED NestMateTidyUp.java

public class NestMateTidyUp {
	
	private static class MemberRef {
		private final HashSet<String> callerNestHostSet = new HashSet<>();

		private void addCaller(ClassRef caller) {
			callerNestHostSet.add(caller.nestHost);
		}

		private void printLooslyAccessibleMembers(String className, String source, String nestHost, String nameAndType) {
			// if all callers are in the same nest
			if (callerNestHostSet.equals(Set.of(nestHost))) {
				System.out.println(className + " in " + source + ", " + nameAndType + " should be declared private");
			}
		}
	}
	
	private static class ClassRef {
		private final String supertype;
		private final String nestHost;
		private final String source;
		private final Map<String, MemberRef> memberMap;
		
		public ClassRef(String supertype, String nestHost, String source, Map<String, MemberRef> memberMap) {
			this.supertype = supertype;
			this.nestHost = Objects.requireNonNull(nestHost);
			this.source = Objects.requireNonNull(source);
			this.memberMap = Map.copyOf(memberMap);
		}

		public void addCaller(ClassRef caller, Repository repository, String nameAndDesc, boolean field) {
			var memberRef = memberMap.get(nameAndDesc);
			if (memberRef == null) {
				if (field) {
				  return;
				}
				var supertypeRef = repository.classRefMap.get(supertype);
				if (supertypeRef == null) {
					return;
				}
				supertypeRef.addCaller(caller, repository, nameAndDesc, false);
				return;
			}
			memberRef.addCaller(caller);
		}

		private void printLooslyAccessibleMembers(String className) {
			memberMap.forEach((nameAndType, memberRef) -> {
				memberRef.printLooslyAccessibleMembers(className, source, nestHost, nameAndType);
			});
		}
	}
	
	private static class Repository {
		private final HashMap<String, ClassRef> classRefMap = new HashMap<>();
		
		private void add(String className, ClassRef classRef) {
			classRefMap.put(className, classRef);
		}

		private Optional<ClassRef> classRef(String className) {
			return Optional.ofNullable(classRefMap.get(className));
		}
		
		private void addCaller(ClassRef caller, String owner, String nameAndDesc, boolean field) {
			var classRef = classRefMap.get(owner);
			if (classRef == null) {
				return;
			}
			classRef.addCaller(caller, this, nameAndDesc, field);
		}
		
		private void printLooslyAccessibleMembers() {
			classRefMap.forEach((className, classRef) -> {
				classRef.printLooslyAccessibleMembers(className);
			});
		}
	}
	
	private static String mangleNameAndDesc(String name, String desc) {
		return name + ' ' + desc;
	}
	
	private static boolean isPackagePrivate(int modifier) {
		return (modifier & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0;
	}
	
	private static String packaze(String className) {
		var index = className.lastIndexOf('/');
		if (index == -1) {
			return "";
		}
		return className.substring(0, index);
	}
	
	private static boolean isSamePackage(String className1, String className2) {
		return packaze(className1).equals(packaze(className2));
	}
	
	private static void scan(ModuleReference moduleReference, Consumer<ClassReader> consumer) throws IOException {
		try(var reader = moduleReference.open()) {
			for(var filename: (Iterable<String>)reader.list()::iterator) {
				if (filename.endsWith(".class")) {
					try(var input = reader.open(filename).orElseThrow()) {
					  var classReader = new ClassReader(input);
					  consumer.accept(classReader);
					}
				}
			}
		}
	}
	
  public static void main(String[] args) throws IOException {
		var finder = ModuleFinder.ofSystem();
		var moduleRef = finder.find("java.base").orElseThrow();
		
		var repository = new Repository();
		
		scan(moduleRef, classReader -> {
			var className = classReader.getClassName();
			classReader.accept(new ClassVisitor(Opcodes.ASM7) {
				private String nestHost = className;
				private String source = className;
				private String supertype;
				private final HashMap<String, MemberRef> members = new HashMap<>();

				@Override
				public void visitSource(String source, String debug) {
					this.source = source;
				}

				@Override
				public void visitNestHost(String nestHost) {
					this.nestHost = nestHost;
				}

				@Override
				public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
					if (superName != null && isSamePackage(className, superName)) {
						supertype = superName;
					}
				}
				
				private void visitMember(int access, String name, String descriptor) {
					if (isPackagePrivate(access)) {
					  members.put(mangleNameAndDesc(name, descriptor), new MemberRef());
					}
				}
				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					visitMember(access, name, descriptor);
					return null;
				}
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					visitMember(access, name, descriptor);
					return null;
				}
				
				@Override
				public void visitEnd() {
					var classRef = new ClassRef(supertype, nestHost, source, members);
					repository.add(className, classRef);
				}
			}, ClassReader.SKIP_CODE);
		});
		
		scan(moduleRef, classReader -> {
			var className = classReader.getClassName();
			var classRef = repository.classRef(className).orElseThrow();
			classReader.accept(new ClassVisitor(Opcodes.ASM7) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM7) {
						private void visitMemberRef(String owner, String name, String descriptor, boolean field) {
							repository.addCaller(classRef, owner, mangleNameAndDesc(name, descriptor), field);
						}
						private void visitHandle(Handle handle) {
							visitMemberRef(handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag() <= Opcodes.H_PUTSTATIC);
						}
						@Override
						public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
							visitMemberRef(owner, name, descriptor, true);
						}
						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
							visitMemberRef(owner, name, descriptor, false);
						}

						@Override
						public void visitLdcInsn(Object value) {
							if (value instanceof Handle) {
								visitHandle((Handle)value);
							}
						}
						@Override
						public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
							visitHandle(bootstrapMethodHandle);
							for(var value: bootstrapMethodArguments) {
								if (value instanceof Handle) {
									visitHandle((Handle)value);
								}
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
		});
		
		repository.printLooslyAccessibleMembers();
	}
}
