import java.io.*;
import java.util.zip.*;

/**
 * General-purpose strip tool: removes ZIP entries whose names start with any of the given prefixes.
 *
 * Usage (Java 17):
 *   CP="patches-src:/usr/share/java/objectweb-asm/asm.jar"
 *
 *   # Strip bundled Guava from kotlin-compiler (use external guava:28.2-jre instead):
 *   java -cp $CP PatchStripBundled /tmp/kc-step1.jar /tmp/kc-step2.jar com/google/
 *
 *   # Strip old SearchScope from intellij-core (kotlin-compiler has a newer version):
 *   java -cp $CP PatchStripBundled lib/intellij-core-1.0.jar lib/intellij-core-1.0-PATCHED.jar \
 *       com/intellij/psi/search/SearchScope
 *
 *   # Multiple prefixes:
 *   java -cp $CP PatchStripBundled input.jar output.jar prefix1/ prefix2/
 */
public class PatchStripBundled {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: PatchStripBundled <input.jar> <output.jar> <prefix> [<prefix>...]");
            System.exit(1);
        }

        String[] prefixes = new String[args.length - 2];
        System.arraycopy(args, 2, prefixes, 0, prefixes.length);

        int stripped = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                boolean skip = false;
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    stripped++;
                    continue;
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Stripped " + stripped + " entries. Done.");
    }
}
