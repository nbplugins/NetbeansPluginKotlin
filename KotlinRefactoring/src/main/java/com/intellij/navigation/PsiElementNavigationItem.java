package com.intellij.navigation;
import com.intellij.psi.PsiElement;
public interface PsiElementNavigationItem extends NavigationItem {
    PsiElement getTargetElement();
}
