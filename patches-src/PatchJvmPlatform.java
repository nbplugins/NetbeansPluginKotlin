import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches kotlin-compiler-1.3.72.jar: converts JvmPlatform from interface to abstract class
 * and injects a getDefaultImports(LanguageVersionSettings) method.
 *
 * Problem: kotlin-converter.jar was compiled against Kotlin 1.1.1, where JvmPlatform was a
 * concrete singleton object with a getDefaultImports() method. In Kotlin 1.3.72 JvmPlatform
 * was refactored into an interface, and getDefaultImports() was moved to
 * JvmPlatformAnalyzerServices. At runtime, kotlin-converter.jar tries to call
 * JvmPlatform.INSTANCE.getDefaultImports() and gets IncompatibleClassChangeError because
 * INSTANCE is now an interface type, not a class.
 *
 * Fix: rewrite the JvmPlatform class file to be an abstract class (not interface) and add
 * the missing getDefaultImports() method delegating to JvmPlatformAnalyzerServices.
 *
 * Run after PatchKtNodeTypes (chain input/output):
 *   java -cp patches-src:lib/asm-all.jar PatchJvmPlatform \
 *     /tmp/kotlin-compiler-patched.jar /tmp/kotlin-compiler-final.jar
 * Then install /tmp/kotlin-compiler-final.jar to ~/.m2 (overwrite the 1.3.72 jar).
 */
public class PatchJvmPlatform {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchJvmPlatform <input.jar> <output.jar>");
            System.exit(1);
        }
        
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {
            
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();
                
                if (name.equals("org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatform.class")) {
                    System.out.println("Patching JvmPlatform...");
                    bytes = patchJvmPlatform(bytes);
                }
                
                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }
    
    static byte[] patchJvmPlatform(byte[] original) {
        ClassReader cr = new ClassReader(original);
        // COMPUTE_FRAMES needed because we're changing class structure
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Avoid classloading during COMPUTE_FRAMES
                return "java/lang/Object";
            }
        };
        
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean hasGetDefaultImports = false;
            
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                // Change from interface to abstract class
                // Remove ACC_INTERFACE, ACC_ABSTRACT stays
                int newAccess = (access & ~Opcodes.ACC_INTERFACE) | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT;
                // JvmPlatform extended TargetPlatform which in 1.3.72 is also different.
                // Use Object as superclass to avoid hierarchy issues.
                super.visit(version, newAccess, name, signature, "java/lang/Object", new String[0]);
                System.out.println("  Changed JvmPlatform from interface to abstract class");
            }
            
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals("getDefaultImports")) {
                    hasGetDefaultImports = true;
                }
                // Keep all existing methods (static init, etc.)
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            
            @Override
            public void visitEnd() {
                if (!hasGetDefaultImports) {
                    addGetDefaultImports();
                }
                super.visitEnd();
            }
            
            void addGetDefaultImports() {
                // public List<ImportPath> getDefaultImports(LanguageVersionSettings settings) {
                //     List<ImportPath> result = new ArrayList<>();
                //     JvmPlatformAnalyzerServices.INSTANCE.computePlatformSpecificDefaultImports(
                //         LockBasedStorageManager.NO_LOCKS, result);
                //     return result;
                // }
                MethodVisitor mv = cw.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    "getDefaultImports",
                    "(Lorg/jetbrains/kotlin/config/LanguageVersionSettings;)Ljava/util/List;",
                    "(Lorg/jetbrains/kotlin/config/LanguageVersionSettings;)Ljava/util/List<Lorg/jetbrains/kotlin/resolve/ImportPath;>;",
                    null
                );
                mv.visitCode();
                
                // ArrayList result = new ArrayList<>()
                mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);
                
                // JvmPlatformAnalyzerServices.INSTANCE.computePlatformSpecificDefaultImports(LockBasedStorageManager.NO_LOCKS, result)
                mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatformAnalyzerServices",
                    "INSTANCE",
                    "Lorg/jetbrains/kotlin/resolve/jvm/platform/JvmPlatformAnalyzerServices;");
                mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/jetbrains/kotlin/storage/LockBasedStorageManager",
                    "NO_LOCKS",
                    "Lorg/jetbrains/kotlin/storage/LockBasedStorageManager;");
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/jetbrains/kotlin/resolve/jvm/platform/JvmPlatformAnalyzerServices",
                    "computePlatformSpecificDefaultImports",
                    "(Lorg/jetbrains/kotlin/storage/StorageManager;Ljava/util/List;)V",
                    false);
                
                // return result
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 3);
                mv.visitEnd();
                System.out.println("  Added getDefaultImports() to JvmPlatform");
            }
        }, 0);
        
        return cw.toByteArray();
    }
}
