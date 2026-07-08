package com.intellij.usageView;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtConstructor;
import org.jetbrains.kotlin.psi.KtFunction;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtTypeAlias;
import java.util.LinkedHashSet;
public final class UsageViewUtil {
    public static String getShortName(PsiElement element) { return element.getText(); }
    public static String createNodeText(PsiElement element) { return element.getText(); }

    /**
     * Stub of the real IDEA {@code getType}, which dispatches through
     * {@code ElementDescriptionUtil}/{@code UsageViewTypeLocation}'s per-language extension points
     * (not available standalone). Real (not faked): mirrors the same declaration-kind naming already
     * used by {@code com.intellij.refactoring.util.RefactoringUIUtil.getDescription} (added for Move
     * Declaration), just returning the bare kind word instead of "kind 'name'".
     */
    public static String getType(PsiElement element) {
        if (element instanceof KtClass) {
            return ((KtClass) element).isInterface() ? "interface" : "class";
        }
        if (element instanceof KtObjectDeclaration) {
            return ((KtObjectDeclaration) element).isCompanion() ? "companion object" : "object";
        }
        if (element instanceof KtTypeAlias) return "type alias";
        if (element instanceof KtConstructor) return "constructor";
        if (element instanceof KtFunction) return "function";
        if (element instanceof KtProperty) return "property";
        if (element instanceof PsiFile) return "file";
        return "declaration";
    }

    /** Dedups by (element, range) identity, same effect as the real implementation's equals-based dedup. */
    public static UsageInfo[] removeDuplicatedUsages(UsageInfo[] usages) {
        return new LinkedHashSet<>(java.util.Arrays.asList(usages)).toArray(new UsageInfo[0]);
    }
}
