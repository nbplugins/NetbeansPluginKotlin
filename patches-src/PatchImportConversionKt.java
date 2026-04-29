import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-converter.jar: replaces JvmPlatform.getDefaultImports() call in
 * ImportConversionKt's static initializer with the 1.3.72-compatible API.
 *
 * Problem: ImportConversionKt (compiled against Kotlin 1.1.1) initializes its DEFAULT_IMPORTS
 * field by calling JvmPlatform.INSTANCE.getDefaultImports(LanguageVersionSettingsImpl.DEFAULT).
 * In Kotlin 1.3.72, this method was moved to JvmPlatformAnalyzerServices, so the call fails
 * at class initialization time with NoSuchMethodError or IncompatibleClassChangeError.
 *
 * Fix: rewrite the static initializer bytecode to call
 * JvmPlatformAnalyzerServices.INSTANCE.computePlatformSpecificDefaultImports(NO_LOCKS, list)
 * instead, which is the equivalent 1.3.72 API.
 *
 * Note: PatchJvmPlatform (which patches kotlin-compiler.jar) is an alternative fix for
 * the same symptom by a different route — apply whichever is sufficient. Both can coexist.
 *
 * Usage: java -cp patches-src:lib/asm-all.jar PatchImportConversionKt \
 *   lib/kotlin-converter.jar.bak lib/kotlin-converter.jar
 * Then re-install: bash setup-local-repo.sh
 */
public class PatchImportConversionKt {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchImportConversionKt <input.jar> <output.jar>");
            System.exit(1);
        }
        
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {
            
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();
                
                if (name.equals("org/jetbrains/kotlin/j2k/ImportConversionKt.class")) {
                    System.out.println("Patching ImportConversionKt...");
                    bytes = patchImportConversionKt(bytes);
                }
                
                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }
    
    static byte[] patchImportConversionKt(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("<clinit>")) {
                    System.out.println("  Replacing static initializer...");
                    return new StaticInitRewriter(mv);
                }
                return mv;
            }
        }, 0);
        
        return cw.toByteArray();
    }
    
    /**
     * Rewrites the static initializer, replacing:
     *   JvmPlatform.INSTANCE.getDefaultImports(LanguageVersionSettingsImpl.DEFAULT)
     * with:
     *   new ArrayList() + JvmPlatformAnalyzerServices.INSTANCE.computePlatformSpecificDefaultImports(NO_LOCKS, list)
     */
    static class StaticInitRewriter extends MethodVisitor {
        // State machine: looking for the JvmPlatform.INSTANCE + getDefaultImports call sequence
        boolean seenJvmPlatformInstance = false;
        boolean patchApplied = false;
        
        StaticInitRewriter(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }
        
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!patchApplied && opcode == Opcodes.GETSTATIC 
                    && owner.equals("org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatform")
                    && name.equals("INSTANCE")) {
                // Instead of loading JvmPlatform.INSTANCE, create ArrayList and set up for compute call
                seenJvmPlatformInstance = true;
                // Don't emit - we'll emit the replacement when we see getDefaultImports
                // Emit a placeholder NOP to keep stack balanced if needed
                // Actually we need to manage the stack carefully.
                // Skip this instruction - we'll handle it in visitMethodInsn
                return;
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
        
        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            if (!patchApplied && seenJvmPlatformInstance
                    && owner.equals("org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatform")
                    && name.equals("getDefaultImports")) {
                
                patchApplied = true;
                seenJvmPlatformInstance = false;
                System.out.println("  Found getDefaultImports call - replacing with computePlatformSpecificDefaultImports");
                
                // We need to consume the LanguageVersionSettings argument that's on the stack
                // (it was pushed before invokevirtual, and "this" = JvmPlatform.INSTANCE which we skipped)
                // Stack currently has: [LanguageVersionSettings]
                // Pop it - we don't need it
                mv.visitInsn(Opcodes.POP);
                
                // Create ArrayList
                mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
                // Stack: [ArrayList]
                
                // Store it temporarily
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                
                // JvmPlatformAnalyzerServices.INSTANCE
                mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatformAnalyzerServices",
                    "INSTANCE",
                    "Lorg/jetbrains/kotlin/resolve/jvm/platform/JvmPlatformAnalyzerServices;");
                
                // LockBasedStorageManager.NO_LOCKS (type is StorageManager in 1.3.72)
                mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/jetbrains/kotlin/storage/LockBasedStorageManager",
                    "NO_LOCKS",
                    "Lorg/jetbrains/kotlin/storage/StorageManager;");
                
                // Load the ArrayList
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                
                // computePlatformSpecificDefaultImports(storageManager, result)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatformAnalyzerServices",
                    "computePlatformSpecificDefaultImports",
                    "(Lorg/jetbrains/kotlin/storage/StorageManager;Ljava/util/List;)V",
                    false);
                
                // Push the result list back
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                // Stack: [ArrayList] - same as the original List result from getDefaultImports
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
