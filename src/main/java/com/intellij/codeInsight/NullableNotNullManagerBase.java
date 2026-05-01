package com.intellij.codeInsight;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete bridge for NullableNotNullManager: implements the package-private
 * getNullityDefault so Kotlin subclasses (which cannot override package-private
 * methods across packages) can extend this class instead.
 */
public abstract class NullableNotNullManagerBase extends NullableNotNullManager {

    protected NullableNotNullManagerBase(Project project) {
        super(project);
    }

    @Override
    @Nullable
    NullabilityAnnotationInfo getNullityDefault(
            PsiModifierListOwner container,
            PsiAnnotation.TargetType[] placeTargetTypes,
            PsiModifierListOwner context,
            boolean skipExternal) {
        return null;
    }
}
