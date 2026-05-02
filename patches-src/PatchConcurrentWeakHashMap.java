import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches intellij-core.jar: adds ConcurrentWeakHashMap(float) constructor.
 *
 * Problem: kotlin-compiler 1.3.72 bundles a newer ContainerUtil whose
 * createConcurrentWeakMap() calls new ConcurrentWeakHashMap(float loadFactor).
 * The intellij-core bundled version only has ConcurrentWeakHashMap(int, float),
 * causing NoSuchMethodError when Disposer initializes.
 *
 * Fix: inject ConcurrentWeakHashMap(float loadFactor) that delegates to
 * this(16, loadFactor, 16, ContainerUtil.canonicalStrategy()).
 * The ContainerUtil.canonicalStrategy() exists in kotlin-compiler's ContainerUtil
 * (intellij-core's ContainerUtil is already stripped per the conflict-resolution rule).
 */
public class PatchConcurrentWeakHashMap {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchConcurrentWeakHashMap <intellij-core.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals("com/intellij/util/containers/ConcurrentWeakHashMap.class")) {
                    System.out.println("Patching ConcurrentWeakHashMap...");
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
                // Add: public ConcurrentWeakHashMap(float loadFactor) {
                //          this(16, loadFactor, 16, ContainerUtil.canonicalStrategy());
                //      }
                MethodVisitor mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    "<init>",
                    "(F)V",
                    null,
                    null
                );
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, 16);           // initialCapacity
                mv.visitVarInsn(Opcodes.FLOAD, 1);             // loadFactor
                mv.visitIntInsn(Opcodes.BIPUSH, 16);           // concurrencyLevel
                mv.visitMethodInsn(                             // ContainerUtil.canonicalStrategy()
                    Opcodes.INVOKESTATIC,
                    "com/intellij/util/containers/ContainerUtil",
                    "canonicalStrategy",
                    "()Lgnu/trove/TObjectHashingStrategy;",
                    false
                );
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "com/intellij/util/containers/ConcurrentWeakHashMap",
                    "<init>",
                    "(IFILgnu/trove/TObjectHashingStrategy;)V",
                    false
                );
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(5, 2);
                mv.visitEnd();

                super.visitEnd();
            }
        }, 0);

        return cw.toByteArray();
    }
}
