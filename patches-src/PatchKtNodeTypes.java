import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-compiler-1.3.72.jar: fixes KtNodeTypes.BODY field descriptor mismatch.
 *
 * Problem: KtNodeTypes.BODY is declared as KtNodeType, but all getstatic/putstatic
 * bytecode instructions in other compiled JARs (e.g. kotlin-formatter.jar) reference it
 * with an IElementType descriptor. Java 17+ enforces strict descriptor matching at
 * field lookup time, so the mismatch causes NoSuchFieldError at runtime.
 *
 * Fix: change the BODY field declaration in KtNodeTypes.class from KtNodeType to
 * IElementType to match all callers. KtNodeType extends IElementType so the value
 * is still assignment-compatible.
 *
 * Usage: java -cp patches-src:lib/asm-all.jar PatchKtNodeTypes \
 *   ~/.m2/repository/org/jetbrains/kotlin/kotlin-compiler/1.3.72/kotlin-compiler-1.3.72.jar \
 *   /tmp/kotlin-compiler-patched.jar
 * Then pass the output to PatchJvmPlatform or install to ~/.m2 directly.
 */
public class PatchKtNodeTypes {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchKtNodeTypes <kotlin-compiler.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals("org/jetbrains/kotlin/KtNodeTypes.class")) {
                    System.out.println("Patching KtNodeTypes...");
                    bytes = patchKtNodeTypes(bytes);
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }

    static byte[] patchKtNodeTypes(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name.equals("BODY") && descriptor.equals("Lorg/jetbrains/kotlin/KtNodeType;")) {
                    System.out.println("  Changing BODY field declaration: KtNodeType -> IElementType");
                    return super.visitField(access, name, "Lcom/intellij/psi/tree/IElementType;", signature, value);
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, 0);

        return cw.toByteArray();
    }
}
