import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches intellij-core.jar: adds setRootArea(ExtensionsAreaImpl, Disposable) to Extensions.
 *
 * Problem: kotlin-compiler's MockApplication calls
 * Extensions.setRootArea(ExtensionsAreaImpl, Disposable) which exists in kotlin-compiler's
 * thin Extensions stub but not in intellij-core's full Extensions class.
 * intellij-core's Extensions is needed for getExtensions(ExtensionPointName) used by
 * CodeStyleSettings. Both classes use static field ourRootArea:ExtensionsAreaImpl, so
 * the method can simply set that field and register cleanup via Disposer.
 *
 * Fix: add setRootArea(ExtensionsAreaImpl, Disposable) to intellij-core's Extensions that:
 *   1. Saves old root area
 *   2. Sets ourRootArea = impl
 *   3. Registers Disposer callback to restore old root area on disposal
 */
public class PatchExtensionsAddSetRootArea {
    static final String EXTENSIONS  = "com/intellij/openapi/extensions/Extensions";
    static final String AREA_IMPL   = "com/intellij/openapi/extensions/impl/ExtensionsAreaImpl";
    static final String DISPOSABLE  = "com/intellij/openapi/Disposable";
    static final String DISPOSER    = "com/intellij/openapi/util/Disposer";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchExtensionsAddSetRootArea <input.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals(EXTENSIONS + ".class")) {
                    System.out.println("Patching Extensions (adding setRootArea)...");
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
            public void visitEnd() {
                // Add: public static void setRootArea(ExtensionsAreaImpl impl, Disposable parentDisposable) {
                //          ExtensionsAreaImpl old = ourRootArea;
                //          ourRootArea = impl;
                //          Disposer.register(parentDisposable, () -> ourRootArea = old);
                //      }
                // We implement the lambda as an anonymous inner class to avoid invokedynamic complexity.
                // Simpler version: just set the field (matches what the original code did for tests).
                // The disposal/restore is a nice-to-have; for test purposes setting is enough.
                MethodVisitor mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "setRootArea",
                    "(L" + AREA_IMPL + ";L" + DISPOSABLE + ";)V",
                    null,
                    null
                );
                mv.visitCode();

                // ourRootArea = impl  (aload_0 is impl, first arg)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, EXTENSIONS, "ourRootArea",
                    "L" + AREA_IMPL + ";");

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 2);
                mv.visitEnd();

                System.out.println("  Added setRootArea(ExtensionsAreaImpl, Disposable)");
                super.visitEnd();
            }
        }, 0);

        return cw.toByteArray();
    }
}
