import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches IdeDescriptorRenderers$BASE$1 in kotlin-ide-common.jar.
 *
 * IdeDescriptorRenderers was compiled against Kotlin 1.1.x which had
 * DescriptorRendererOptions.showInternalKeyword. In Kotlin 1.3.72 this
 * property was removed. The call crashes the static initializer of
 * IdeDescriptorRenderers.
 *
 * Fix: replace the invokeinterface call to setShowInternalKeyword (and its
 * two preceding stack-push instructions) with two POP instructions that
 * discard the receiver and boolean argument already on the stack.
 */
public class PatchIdeDescriptorRenderers {
    static final String TARGET = "org/jetbrains/kotlin/idea/util/IdeDescriptorRenderers$BASE$1";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchIdeDescriptorRenderers <input.jar> <output.jar>");
            System.exit(1);
        }

        int[] patched = {0};
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                String name = entry.getName();

                if (name.equals(TARGET + ".class")) {
                    data = patchClass(data, patched);
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("Patched " + patched[0] + " call(s) to setShowInternalKeyword");
    }

    static byte[] patchClass(byte[] classBytes, int[] patched) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String mdesc, boolean itf) {
                        if ("setShowInternalKeyword".equals(mname)) {
                            // Stack has: [receiver, boolean] — consume both without calling
                            mv.visitInsn(Opcodes.POP);  // boolean
                            mv.visitInsn(Opcodes.POP);  // receiver
                            patched[0]++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }
}
