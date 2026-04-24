import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Removes from a JAR all .class entries that already exist in a reference JAR.
 *
 * Problem: intellij-core.jar bundles old versions of IntelliJ platform classes
 * (e.g. StubBasedPsiElementBase, SubstrateRef). kotlin-compiler-1.3.72.jar is a
 * fat jar that also bundles newer versions of those same classes — versions that
 * include APIs like getGreenStub() that the old copies lack. Since intellij-core.jar
 * appears first on the classpath, the JVM loads the old broken versions instead of
 * the newer working ones from kotlin-compiler.jar.
 *
 * Fix: strip all .class entries from intellij-core.jar that are already present in
 * kotlin-compiler.jar, so the JVM falls through to the newer versions.
 *
 * Usage: java -cp patches-src:lib/asm-all.jar StripDuplicates \
 *   lib/intellij-core.jar.bak \
 *   ~/.m2/repository/org/jetbrains/kotlin/kotlin-compiler/1.3.72/kotlin-compiler-1.3.72.jar \
 *   lib/intellij-core.jar
 * Then re-install: bash setup-local-repo.sh
 */
public class StripDuplicates {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: StripDuplicates <input.jar> <reference.jar> <output.jar>");
            System.exit(1);
        }
        File inputJar = new File(args[0]);
        File referenceJar = new File(args[1]);
        File outputJar = new File(args[2]);

        // Collect all entry names from the reference JAR
        Set<String> referenceEntries = new HashSet<>();
        try (ZipInputStream ref = new ZipInputStream(new FileInputStream(referenceJar))) {
            ZipEntry entry;
            while ((entry = ref.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) {
                    referenceEntries.add(entry.getName());
                }
            }
        }
        System.out.println("Reference JAR has " + referenceEntries.size() + " .class entries");

        int kept = 0, stripped = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(inputJar));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outputJar))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.endsWith(".class") && referenceEntries.contains(name)) {
                    stripped++;
                    // skip — reference JAR has a newer version
                } else {
                    ZipEntry outEntry = new ZipEntry(name);
                    zout.putNextEntry(outEntry);
                    zout.write(bytes);
                    zout.closeEntry();
                    kept++;
                }
            }
        }
        System.out.println("Kept: " + kept + " entries, stripped: " + stripped + " duplicate .class files");
    }
}
