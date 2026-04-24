import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-ide-common.jar for compatibility with kotlin-compiler-1.3.72:
 *
 * 1. IdeDescriptorRenderers$BASE$1: remove setShowInternalKeyword call
 *    (property removed from DescriptorRendererOptions in 1.3.72)
 *
 * 2. TypeUtils: KotlinTypeFactory.simpleType(5 args with MemberScope)
 *    -> simpleTypeWithNonTrivialMemberScope (renamed in 1.3.72)
 *
 * 3. TypeUtils: KotlinType.isError() invokevirtual/invokeinterface
 *    -> KotlinTypeKt.isError(KotlinType) invokestatic (became extension in 1.3.72)
 */
public class PatchKotlinIdeCommon {
    static final String SIMPLE_TYPE_SIG =
        "(Lorg/jetbrains/kotlin/descriptors/annotations/Annotations;" +
        "Lorg/jetbrains/kotlin/types/TypeConstructor;" +
        "Ljava/util/List;Z" +
        "Lorg/jetbrains/kotlin/resolve/scopes/MemberScope;)" +
        "Lorg/jetbrains/kotlin/types/SimpleType;";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchKotlinIdeCommon <input.jar> <output.jar>");
            System.exit(1);
        }

        int[] patched = {0};
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                String name = entry.getName();

                if (name.endsWith(".class")) {
                    data = patchClass(name, data, patched);
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("Patched " + patched[0] + " site(s) in kotlin-ide-common.jar");
    }

    static byte[] patchClass(String name, byte[] classBytes, int[] patched) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, mname, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mmname,
                                                String mdesc, boolean itf) {
                        // Fix 1: remove setShowInternalKeyword call
                        if ("setShowInternalKeyword".equals(mmname)) {
                            mv.visitInsn(Opcodes.POP); // boolean
                            mv.visitInsn(Opcodes.POP); // receiver
                            patched[0]++;
                            return;
                        }

                        // Fix 2: simpleType(5-arg with MemberScope) -> simpleTypeWithNonTrivialMemberScope
                        if ("org/jetbrains/kotlin/types/KotlinTypeFactory".equals(owner)
                                && "simpleType".equals(mmname)
                                && SIMPLE_TYPE_SIG.equals(mdesc)) {
                            super.visitMethodInsn(opcode, owner, "simpleTypeWithNonTrivialMemberScope",
                                    mdesc, itf);
                            patched[0]++;
                            return;
                        }

                        // Fix 3: KotlinType.isError() -> KotlinTypeKt.isError(KotlinType) static
                        if ("org/jetbrains/kotlin/types/KotlinType".equals(owner)
                                && "isError".equals(mmname)
                                && "()Z".equals(mdesc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "org/jetbrains/kotlin/types/KotlinTypeKt",
                                    "isError",
                                    "(Lorg/jetbrains/kotlin/types/KotlinType;)Z",
                                    false);
                            patched[0]++;
                            return;
                        }

                        super.visitMethodInsn(opcode, owner, mmname, mdesc, itf);
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }
}
