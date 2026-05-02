import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Strips from the primary JAR all .class entries that also exist in the reference JAR,
 * except entries matching any prefix in the exclusion list.
 *
 * Problem: intellij-core-1.0.jar bundles old IntelliJ platform classes that conflict with
 * newer versions of the same classes bundled in kotlin-compiler-1.3.72.jar. When both are on
 * the classpath, whichever appears first wins, but callers in the newer JAR expect newer APIs
 * (new constructors, new methods) that the older versions lack.
 *
 * Fix (per project rule): keep NEW classes (from kotlin-compiler), strip OLD classes (from
 * intellij-core). This tool automates the strip by computing the intersection.
 *
 * Exclusions: some classes must stay from intellij-core because the kotlin-compiler version
 * requires infrastructure (e.g., Registry bundles) not present in the test environment.
 *
 * Usage:
 *   java -cp patches-src PatchStripConflicts \
 *       lib/intellij-core-1.0-PATCHED.jar \
 *       lib/kotlin-compiler-1.3.72-PATCHED.jar \
 *       lib/intellij-core-1.0-PATCHED.jar \
 *       [exclude:prefix1 exclude:prefix2 ...]
 *   (input and output may be the same path — the tool writes to a temp file first)
 */
public class PatchStripConflicts {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: PatchStripConflicts <primary.jar> <reference.jar> <output.jar> [exclude:prefix ...]");
            System.exit(1);
        }
        File primaryFile = new File(args[0]);
        File referenceFile = new File(args[1]);
        File outputFile = new File(args[2]);

        Set<String> excludePrefixes = new HashSet<>();
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("exclude:")) {
                excludePrefixes.add(args[i].substring("exclude:".length()));
            }
        }
        if (!excludePrefixes.isEmpty()) {
            System.out.println("Exclusion prefixes: " + excludePrefixes);
        }

        // Collect all class entry names from the reference JAR
        Set<String> referenceClasses = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(referenceFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    referenceClasses.add(name);
                }
                zin.closeEntry();
            }
        }
        System.out.println("Reference JAR has " + referenceClasses.size() + " class entries.");

        // Strip from primary JAR all entries found in reference JAR (unless excluded)
        File tempFile = File.createTempFile("strip-conflicts-", ".jar");
        int stripped = 0;
        int kept = 0;
        int excluded = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(primaryFile));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tempFile))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();
                zin.closeEntry();

                if (name.endsWith(".class") && referenceClasses.contains(name)) {
                    boolean isExcluded = false;
                    for (String prefix : excludePrefixes) {
                        if (name.startsWith(prefix)) {
                            isExcluded = true;
                            break;
                        }
                    }
                    if (!isExcluded) {
                        stripped++;
                        continue;
                    } else {
                        excluded++;
                    }
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
                kept++;
            }
        }

        // Move temp to output (handles in-place update across filesystems)
        Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Stripped " + stripped + " conflicting entries, excluded " + excluded + " from stripping, kept " + kept + ". Done.");
    }
}
