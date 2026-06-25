package com.intellij.usageView;
import com.intellij.psi.PsiElement;
public final class UsageViewUtil {
    public static String getShortName(PsiElement element) { return element.getText(); }
    public static String createNodeText(PsiElement element) { return element.getText(); }
}
