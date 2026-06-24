package com.intellij.usages;
import com.intellij.psi.PsiElement;
public interface PsiElementUsageTarget extends UsageTarget {
    PsiElement getElement();
}
