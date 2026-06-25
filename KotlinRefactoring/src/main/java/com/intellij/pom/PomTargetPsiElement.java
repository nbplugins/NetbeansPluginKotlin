package com.intellij.pom;
import com.intellij.psi.PsiElement;
public interface PomTargetPsiElement extends PsiElement {
    PomTarget getTarget();
}
