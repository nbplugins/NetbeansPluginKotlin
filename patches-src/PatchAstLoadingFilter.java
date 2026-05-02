import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-compiler-1.3.72.jar: stubs out AstLoadingFilter.assertTreeLoadingAllowed()
 * so it always returns without consulting the Registry.
 *
 * Problem: kotlin-compiler's AstLoadingFilter.assertTreeLoadingAllowed() calls
 * Registry.is("ast.loading.filter") which reads from a PropertyResourceBundle.
 * That bundle key is absent in the test environment, causing MissingResourceException
 * when PsiFileImpl.loadTreeElement() is called.
 *
 * Fix: replace the method body with a single RETURN instruction.
 * This is safe for tests: it means tree loading is always allowed.
 */
public class PatchAstLoadingFilter {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchAstLoadingFilter <input.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals("com/intellij/util/AstLoadingFilter.class")) {
                    System.out.println("Patching AstLoadingFilter...");
                    bytes = patchClass(bytes);
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }

    static byte[] patchClass(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("assertTreeLoadingAllowed".equals(name)) {
                    System.out.println("  Stubbing assertTreeLoadingAllowed" + descriptor);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitInsn(Opcodes.RETURN);
                        }
                        // Drop all original instructions after visitCode
                        @Override public void visitFieldInsn(int op, String o, String n, String d) {}
                        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean i) {}
                        @Override public void visitInsn(int op) {}
                        @Override public void visitVarInsn(int op, int var) {}
                        @Override public void visitLdcInsn(Object cst) {}
                        @Override public void visitJumpInsn(int op, Label l) {}
                        @Override public void visitLabel(Label l) {}
                        @Override public void visitTypeInsn(int op, String t) {}
                        @Override public void visitIntInsn(int op, int operand) {}
                        @Override public void visitFrame(int type, int nl, Object[] ll, int ns, Object[] sl) {}
                        @Override public void visitIincInsn(int var, int increment) {}
                        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}
                        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}
                        @Override public void visitMultiANewArrayInsn(String descriptor, int dims) {}
                        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) {}
                        @Override public void visitLocalVariable(String n, String d, String sg, Label s, Label e, int i) {}
                        @Override public void visitLineNumber(int line, Label start) {}
                        @Override public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(0, maxLocals);
                        }
                    };
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }
}
