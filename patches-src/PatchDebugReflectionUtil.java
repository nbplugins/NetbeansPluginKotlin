import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches DebugReflectionUtil in intellij-core.jar for Java 17 compatibility.
 *
 * processStronglyReferencedValues() does deep reflection over arbitrary objects
 * to find PSI references — a debug/assertion utility. Under Java 17's module
 * system, accessing fields of JDK classes throws InaccessibleObjectException /
 * IllegalAccessException, crashing the analysis.
 *
 * Fix: stub processStronglyReferencedValues() to return true immediately,
 * skipping the reflection traversal. The caller (CachedValueChecker) treats
 * true as "no problematic references found" and continues normally.
 *
 * Also: wrap Field.setAccessible(true) in getAllFields() with try-catch to
 * avoid crashes from that path as well.
 */
public class PatchDebugReflectionUtil {
    static final String TARGET_CLASS = "com/intellij/util/DebugReflectionUtil";
    static final String PROCESS_SIG =
            "(Ljava/lang/Object;Lcom/intellij/util/PairProcessor;)Z";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchDebugReflectionUtil <input.jar> <output.jar>");
            System.exit(1);
        }

        int[] patched = {0};
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();

                if (entry.getName().equals(TARGET_CLASS + ".class")) {
                    data = patchClass(data, patched);
                }

                ZipEntry outEntry = new ZipEntry(entry.getName());
                zout.putNextEntry(outEntry);
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("Patched " + patched[0] + " method(s) in " + TARGET_CLASS);
    }

    static byte[] patchClass(byte[] classBytes, int[] patched) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

                // Stub processStronglyReferencedValues to return true immediately
                if ("processStronglyReferencedValues".equals(name) && PROCESS_SIG.equals(desc)) {
                    patched[0]++;
                    System.out.println("  Stubbing " + name);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        boolean stubbed = false;

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // Insert: return true; at the very beginning
                            mv.visitInsn(Opcodes.ICONST_1);
                            mv.visitInsn(Opcodes.IRETURN);
                            stubbed = true;
                        }
                    };
                }

                // Wrap Field.setAccessible in getAllFields with try-catch
                if ("getAllFields".equals(name)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname,
                                                    String mdesc, boolean itf) {
                            if ("java/lang/reflect/Field".equals(owner)
                                    && "setAccessible".equals(mname)) {
                                Label tryStart = new Label();
                                Label tryEnd = new Label();
                                Label handler = new Label();
                                Label after = new Label();
                                mv.visitTryCatchBlock(tryStart, tryEnd, handler,
                                        "java/lang/Exception");
                                mv.visitLabel(tryStart);
                                super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                                mv.visitLabel(tryEnd);
                                mv.visitJumpInsn(Opcodes.GOTO, after);
                                mv.visitLabel(handler);
                                mv.visitInsn(Opcodes.POP);
                                mv.visitLabel(after);
                                patched[0]++;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                        }
                    };
                }

                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }
}
