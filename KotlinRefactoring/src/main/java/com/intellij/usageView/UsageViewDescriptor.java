package com.intellij.usageView;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
public interface UsageViewDescriptor {
    PsiElement @NotNull [] getElements();
    @NotNull String getProcessedElementsHeader();
    @NotNull String getCodeReferencesText(int usagesCount, int filesCount);
    default String getCommentReferencesText(int usagesCount, int filesCount) { return ""; }
}
