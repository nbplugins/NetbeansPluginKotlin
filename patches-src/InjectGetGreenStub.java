import org.objectweb.asm.*;
import org.objectweb.asm.ClassWriter;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Patches intellij-core.jar to add getGreenStub() to SubstrateRef and StubBasedPsiElementBase.
 *
 * Problem: kotlin-compiler 1.3.72 calls KtAnnotationEntry.getGreenStub() (inherited from
 * StubBasedPsiElementBase) during stub-based PSI resolution. The bundled intellij-core.jar
 * predates this API — the method was added to the IntelliJ platform later and is missing.
 *
 * Fix: inject the method into SubstrateRef (delegates to getStub()) and into
 * StubBasedPsiElementBase (delegates to mySubstrateRef.getGreenStub()).
 *
 * Usage: java -cp patches-src:lib/asm-all.jar InjectGetGreenStub lib/intellij-core.jar.bak lib/intellij-core.jar
 * Then re-install: bash setup-local-repo.sh
 */
public class InjectGetGreenStub {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: InjectGetGreenStub <intellij-core.jar> <output.jar>");
            System.exit(1);
        }
        File inputJar = new File(args[0]);
        File outputJar = new File(args[1]);
        
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(inputJar));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outputJar))) {
            
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();
                
                if (name.equals("com/intellij/psi/impl/source/SubstrateRef.class")) {
                    System.out.println("Patching SubstrateRef...");
                    bytes = patchSubstrateRef(bytes);
                } else if (name.equals("com/intellij/extapi/psi/StubBasedPsiElementBase.class")) {
                    System.out.println("Patching StubBasedPsiElementBase...");
                    bytes = patchStubBasedPsiElementBase(bytes);
                }
                
                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }
    
    static byte[] patchSubstrateRef(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean hasGetGreenStub = false;
            
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("getGreenStub")) hasGetGreenStub = true;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            
            @Override
            public void visitEnd() {
                if (!hasGetGreenStub) {
                    // public Stub getGreenStub() { return getStub(); }
                    MethodVisitor mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "getGreenStub",
                        "()Lcom/intellij/psi/stubs/Stub;",
                        null, null
                    );
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "com/intellij/psi/impl/source/SubstrateRef",
                        "getStub",
                        "()Lcom/intellij/psi/stubs/Stub;",
                        false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                    System.out.println("  Added getGreenStub() to SubstrateRef");
                } else {
                    System.out.println("  SubstrateRef already has getGreenStub()");
                }
                super.visitEnd();
            }
        }, 0);
        
        return cw.toByteArray();
    }
    
    static byte[] patchStubBasedPsiElementBase(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean hasGetGreenStub = false;
            
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("getGreenStub")) hasGetGreenStub = true;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            
            @Override
            public void visitEnd() {
                if (!hasGetGreenStub) {
                    // public T getGreenStub() { return (T) mySubstrateRef.getGreenStub(); }
                    MethodVisitor mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "getGreenStub",
                        "()Lcom/intellij/psi/stubs/StubElement;",
                        "()TT;",
                        null
                    );
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(Opcodes.GETFIELD,
                        "com/intellij/extapi/psi/StubBasedPsiElementBase",
                        "mySubstrateRef",
                        "Lcom/intellij/psi/impl/source/SubstrateRef;");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "com/intellij/psi/impl/source/SubstrateRef",
                        "getGreenStub",
                        "()Lcom/intellij/psi/stubs/Stub;",
                        false);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "com/intellij/psi/stubs/StubElement");
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                    System.out.println("  Added getGreenStub() to StubBasedPsiElementBase");
                } else {
                    System.out.println("  StubBasedPsiElementBase already has getGreenStub()");
                }
                super.visitEnd();
            }
        }, 0);
        
        return cw.toByteArray();
    }
}
