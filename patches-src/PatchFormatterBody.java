import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-formatter.jar: fixes field descriptor mismatch for KtNodeTypes.BODY.
 *
 * Problem: kotlin-formatter.jar was compiled against Kotlin 1.1.1, where KtNodeTypes.BODY
 * was declared as KtNodeType. The bytecode contains:
 *   getstatic KtNodeTypes.BODY Lorg/jetbrains/kotlin/KtNodeType;
 * In kotlin-compiler-1.3.72.jar, BODY is declared as IElementType (the parent type).
 * Java 17+ enforces strict field descriptor matching at getstatic/putstatic resolution,
 * causing NoSuchFieldError: BODY at runtime.
 *
 * Fix: rewrite all getstatic/putstatic instructions in kotlin-formatter.jar that reference
 * KtNodeTypes.BODY with descriptor KtNodeType, changing the descriptor to IElementType.
 * The value is assignment-compatible since KtNodeType extends IElementType.
 *
 * Usage: java -cp patches-src:lib/asm-all.jar PatchFormatterBody \
 *   lib/kotlin-formatter.jar.bak lib/kotlin-formatter.jar
 * Then re-install: bash setup-local-repo.sh
 */
public class PatchFormatterBody {

    static final String KT_NODE_TYPES = "org/jetbrains/kotlin/KtNodeTypes";
    static final String OLD_DESC = "Lorg/jetbrains/kotlin/KtNodeType;";
    static final String NEW_DESC = "Lcom/intellij/psi/tree/IElementType;";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchFormatterBody <input.jar> <output.jar>");
            System.exit(1);
        }
        int[] patched = {0};

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.endsWith(".class")) {
                    byte[] newBytes = patchClass(bytes, name, patched);
                    bytes = newBytes;
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Total BODY field references patched: " + patched[0]);
    }

    static byte[] patchClass(byte[] original, String className, int[] counter) {
        ClassReader cr = new ClassReader(original);
        final boolean[] changed = {false};
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String desc) {
                        if (KT_NODE_TYPES.equals(owner)
                                && OLD_DESC.equals(desc)) {
                            System.out.println("  Patching " + fieldName + " reference in " + className + "." + name);
                            changed[0] = true;
                            counter[0]++;
                            super.visitFieldInsn(opcode, owner, fieldName, NEW_DESC);
                        } else {
                            super.visitFieldInsn(opcode, owner, fieldName, desc);
                        }
                    }
                };
            }
        }, 0);

        return changed[0] ? cw.toByteArray() : original;
    }
}
