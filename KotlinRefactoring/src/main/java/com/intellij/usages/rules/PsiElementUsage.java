package com.intellij.usages.rules;
import com.intellij.psi.PsiElement;
import com.intellij.usages.Usage;
public interface PsiElementUsage extends Usage {
    PsiElement getElement();
    boolean isNonCodeUsage();
}
