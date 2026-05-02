import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches intellij-core.jar: fixes ConcurrentRefHashMap to call newConcurrentMap(int,float,int)
 * instead of newConcurrentMap(int,float,int,TObjectHashingStrategy).
 *
 * Problem: intellij-core's ConcurrentRefHashMap(int,float,int,TObjectHashingStrategy) calls
 * ContainerUtil.newConcurrentMap(int,float,int,TObjectHashingStrategy) — a 4-argument overload
 * that was removed from the newer ContainerUtil bundled in kotlin-compiler-1.3.72.jar.
 * intellij-core's ContainerUtil is already stripped (newer one from kotlin-compiler wins),
 * so the 4-arg call fails with NoSuchMethodError at runtime.
 *
 * Fix: intercept the 4-arg invokestatic and replace with 3-arg (pop the strategy arg from stack).
 * The intellij-core code always pushed ContainerUtil.CANONICAL as the 4th arg anyway — it never
 * used the instance hashingStrategy when constructing the inner map — so dropping it is safe.
 */
public class PatchConcurrentRefHashMap {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchConcurrentRefHashMap <intellij-core.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals("com/intellij/util/containers/ConcurrentRefHashMap.class")) {
                    System.out.println("Patching ConcurrentRefHashMap...");
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
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && "com/intellij/util/containers/ContainerUtil".equals(owner)
                            && "newConcurrentMap".equals(mname)
                            && "(IFILgnu/trove/TObjectHashingStrategy;)Ljava/util/concurrent/ConcurrentMap;".equals(desc)) {
                            System.out.println("  Redirecting ContainerUtil.newConcurrentMap(4-arg) -> (3-arg) in " + name);
                            // Pop the TObjectHashingStrategy reference that was already pushed
                            super.visitInsn(Opcodes.POP);
                            // Call the 3-arg version that exists in kotlin-compiler's ContainerUtil
                            super.visitMethodInsn(opcode, owner, mname,
                                "(IFI)Ljava/util/concurrent/ConcurrentMap;", itf);
                        } else {
                            super.visitMethodInsn(opcode, owner, mname, desc, itf);
                        }
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }
}
