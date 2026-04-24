import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-converter.jar:
 * 1. ImportPath.fqnPart() -> ImportPath.getFqName() (renamed in Kotlin 1.3.72)
 * 2. AddToStdlibKt.singletonList() -> Collections.singletonList() (removed in 1.3.72)
 */
public class PatchFqnPart {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchFqnPart <input.jar> <output.jar>");
            System.exit(1);
        }

        int[] patched = {0};
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    data = patchClass(data, patched);
                }
                ZipEntry outEntry = new ZipEntry(entry.getName());
                zout.putNextEntry(outEntry);
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("Patched " + patched[0] + " fqnPart() call(s)");
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
                        if ("org/jetbrains/kotlin/resolve/ImportPath".equals(owner)
                                && "fqnPart".equals(mname)) {
                            super.visitMethodInsn(opcode, owner, "getFqName", mdesc, itf);
                            patched[0]++;
                            return;
                        }
                        if ("org/jetbrains/kotlin/utils/addToStdlib/AddToStdlibKt".equals(owner)
                                && "singletonList".equals(mname)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "java/util/Collections", "singletonList",
                                    "(Ljava/lang/Object;)Ljava/util/List;", false);
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
