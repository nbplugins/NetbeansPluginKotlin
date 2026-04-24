import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches intellij-core.jar: adds contains(VirtualFile) to SearchScope and LocalSearchScope.
 *
 * Problem: kotlin-compiler-1.3.72.jar calls SearchScope.contains(VirtualFile) (added in a newer
 * IntelliJ version), but the bundled intellij-core.jar has an older SearchScope without this method.
 * At runtime, JavaClassImpl.isFromSourceCodeInScope() calls scope.contains(virtualFile) which
 * fails with NoSuchMethodError.
 *
 * Fix:
 *   1. Add abstract contains(VirtualFile) to SearchScope (so invokevirtual resolves correctly).
 *   2. Add contains(VirtualFile) to LocalSearchScope delegating to isInScope(VirtualFile).
 *      (GlobalSearchScope already declares contains(VirtualFile) as abstract — its subclasses
 *       implement it, so no change needed there.)
 *
 * Usage: java -cp patches-src:lib/asm-all.jar PatchSearchScope \
 *   lib/intellij-core.jar.bak lib/intellij-core.jar
 * Then re-install: bash setup-local-repo.sh
 */
public class PatchSearchScope {

    static final String SEARCH_SCOPE = "com/intellij/psi/search/SearchScope";
    static final String LOCAL_SEARCH_SCOPE = "com/intellij/psi/search/LocalSearchScope";
    static final String VIRTUAL_FILE = "com/intellij/openapi/vfs/VirtualFile";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchSearchScope <input.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals(SEARCH_SCOPE + ".class")) {
                    System.out.println("Patching SearchScope...");
                    bytes = patchSearchScope(bytes);
                } else if (name.equals(LOCAL_SEARCH_SCOPE + ".class")) {
                    System.out.println("Patching LocalSearchScope...");
                    bytes = patchLocalSearchScope(bytes);
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }

    // Adds: public abstract boolean contains(VirtualFile file);
    static byte[] patchSearchScope(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean hasContains = false;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals("contains")) hasContains = true;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (!hasContains) {
                    // Add: public abstract boolean contains(VirtualFile file)
                    MethodVisitor mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "contains",
                        "(L" + VIRTUAL_FILE + ";)Z",
                        null, null);
                    mv.visitEnd();
                    System.out.println("  Added abstract contains(VirtualFile) to SearchScope");
                }
                super.visitEnd();
            }
        }, 0);

        return cw.toByteArray();
    }

    // Adds: public boolean contains(VirtualFile file) { return isInScope(file); }
    static byte[] patchLocalSearchScope(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean hasContains = false;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals("contains")) hasContains = true;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (!hasContains) {
                    // Add: public boolean contains(VirtualFile file) { return isInScope(file); }
                    MethodVisitor mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "contains",
                        "(L" + VIRTUAL_FILE + ";)Z",
                        null, null);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        LOCAL_SEARCH_SCOPE,
                        "isInScope",
                        "(L" + VIRTUAL_FILE + ";)Z",
                        false);
                    mv.visitInsn(Opcodes.IRETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    System.out.println("  Added contains(VirtualFile) to LocalSearchScope");
                }
                super.visitEnd();
            }
        }, 0);

        return cw.toByteArray();
    }
}
