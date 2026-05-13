package com.intellij.ide.plugins;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Stub for {@code com.intellij.ide.plugins.DataLoader} from {@code core-impl:242}.
 *
 * <p>The 242-era interface added {@code load(String, boolean)} as the primary abstract method.
 * Analysis-api 2.0.21 implements only {@code load(String)} (compiled against an older interface).
 * This stub bridges the gap: it declares {@code load(String)} as abstract (so existing
 * implementations compile) and provides a {@code default load(String, boolean)} that delegates
 * to {@code load(String)}, ignoring the {@code ignoreErrors} flag.
 */
public interface DataLoader {

    /** Returns the data stream for {@code path}, or {@code null} if not found. */
    InputStream load(String path) throws Exception;

    /**
     * 242-era variant — ignores {@code ignoreErrors} and delegates to {@link #load(String)}.
     * Added as a default so that implementations compiled against older versions of this
     * interface (e.g. {@code PluginStructureProvider$ResourceDataLoader} in analysis-api 2.0.21)
     * satisfy the contract expected by core-impl:242 call sites.
     */
    default InputStream load(String path, boolean ignoreErrors) {
        try {
            return load(path);
        } catch (Exception e) {
            if (ignoreErrors) return null;
            throw new RuntimeException(e);
        }
    }

    default boolean getEmptyDescriptorIfCannotResolve() {
        return false;
    }

    default boolean isExcludedFromSubSearch(Path path) {
        return false;
    }
}
