package com.intellij.usageView;
import com.intellij.psi.PsiElement;
import java.util.LinkedHashSet;
public final class UsageViewUtil {
    public static String getShortName(PsiElement element) { return element.getText(); }
    public static String createNodeText(PsiElement element) { return element.getText(); }

    /** Dedups by (element, range) identity, same effect as the real implementation's equals-based dedup. */
    public static UsageInfo[] removeDuplicatedUsages(UsageInfo[] usages) {
        return new LinkedHashSet<>(java.util.Arrays.asList(usages)).toArray(new UsageInfo[0]);
    }
}
