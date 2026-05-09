package com.intellij.codeInsight;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierListOwner;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete bridge for NullableNotNullManager: exposes the package-private
 * getNullityDefault and supplies a default getAnnotationNullability so Kotlin
 * subclasses can extend this class.
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
            PsiElement context,
            boolean skipExternal) {
        return null;
    }

    @Override
    public Optional<Nullability> getAnnotationNullability(String name) {
        return Optional.empty();
    }

    @Override
    public boolean isTypeUseAnnotationLocationRestricted(String name) {
        return false;
    }

    @Override
    public boolean canAnnotateLocals(String name) {
        return true;
    }

    @Override
    protected NullableNotNullManager.NullabilityAnnotationDataHolder getAllNullabilityAnnotationsWithNickNames() {
        return new NullableNotNullManager.NullabilityAnnotationDataHolder() {
            @Override
            public java.util.Set<String> qualifiedNames() {
                return java.util.Collections.emptySet();
            }

            @Override
            public Nullability getNullability(String s) {
                return null;
            }
        };
    }
}
