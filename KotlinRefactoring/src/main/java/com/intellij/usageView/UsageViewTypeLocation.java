package com.intellij.usageView;
import com.intellij.psi.PsiElement;
import com.intellij.psi.ElementDescriptionLocation;
public final class UsageViewTypeLocation extends ElementDescriptionLocation {
    public static final UsageViewTypeLocation INSTANCE = new UsageViewTypeLocation();
    private UsageViewTypeLocation() {}
}
