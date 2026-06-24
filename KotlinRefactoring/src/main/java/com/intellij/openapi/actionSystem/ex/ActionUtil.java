package com.intellij.openapi.actionSystem.ex;
import com.intellij.openapi.project.Project;
import java.util.function.Supplier;
public final class ActionUtil {
    public static boolean isDumbMode(Project project) { return false; }
    /** Stub: runs block directly without modal progress. */
    public static <T> T underModalProgress(Project project, String title, Supplier<T> block) {
        return block.get();
    }
}
