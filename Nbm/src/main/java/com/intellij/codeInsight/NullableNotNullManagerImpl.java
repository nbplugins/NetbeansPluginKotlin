/*******************************************************************************
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package com.intellij.codeInsight;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Standalone implementation of the IntelliJ {@link NullableNotNullManager} service.
 *
 * The abstract base class ships inside the repacked {@code kotlin-compiler} JAR, but no
 * implementation is published in any artifact of the bundled platform, so every static helper
 * ({@link NullableNotNullManager#getNullability}, {@code isNullable}, {@code isNotNull}) failed
 * with a service-not-found error. Registering this class on the standalone analysis session
 * restores annotation-based nullability inference for the Java-to-Kotlin converter.
 *
 * <p>The class must live in {@code com.intellij.codeInsight} because
 * {@link #getNullityDefault} overrides a package-private abstract method.
 *
 * <p>Compared to the IDEA implementation this one is deliberately reduced. Nickname annotation
 * classes ({@code @TypeQualifierNickname}) and Checker Framework default qualifiers are not
 * scanned: both require indexes and utility classes absent from the bundled platform. Only
 * directly-declared nullability annotations and JSR-305 {@code @TypeQualifierDefault} defaults
 * are honoured, which covers ordinary Java sources.
 */
public class NullableNotNullManagerImpl extends NullableNotNullManager {

    /** Fully qualified name of the JSR-305 meta-annotation that marks default qualifiers. */
    public static final String TYPE_QUALIFIER_DEFAULT = "javax.annotation.meta.TypeQualifierDefault";

    /** JSR-305 nullable annotation, also used as a qualifier inside a default container. */
    private static final String JAVAX_ANNOTATION_NULLABLE = "javax.annotation.Nullable";

    /** JSR-305 non-null annotation, also used as a qualifier inside a default container. */
    private static final String JAVAX_ANNOTATION_NONNULL = "javax.annotation.Nonnull";

    /** JetBrains not-null annotation, the default value of {@link #getDefaultNotNull()}. */
    private static final String NOT_NULL = "org.jetbrains.annotations.NotNull";

    /** JetBrains nullable annotation, the default value of {@link #getDefaultNullable()}. */
    private static final String NULLABLE = "org.jetbrains.annotations.Nullable";

    /**
     * Annotation names treated as nullable by default.
     *
     * Copied from the IDEA implementation because the bundled base class does not ship its own
     * arrays; without a predefined list the manager would know no annotations at all.
     */
    private static final String[] DEFAULT_NULLABLES = {
        NULLABLE,
        JAVAX_ANNOTATION_NULLABLE,
        "javax.annotation.CheckForNull",
        "edu.umd.cs.findbugs.annotations.Nullable",
        "android.support.annotation.Nullable",
        "androidx.annotation.Nullable",
        "androidx.annotation.RecentlyNullable",
        "org.checkerframework.checker.nullness.qual.Nullable",
        "org.checkerframework.checker.nullness.compatqual.NullableDecl",
        "org.checkerframework.checker.nullness.compatqual.NullableType",
        "com.android.annotations.Nullable",
    };

    /**
     * Annotation names treated as not-null by default.
     *
     * See {@link #DEFAULT_NULLABLES} for why the list is duplicated here.
     */
    private static final String[] DEFAULT_NOT_NULLS = {
        NOT_NULL,
        JAVAX_ANNOTATION_NONNULL,
        "edu.umd.cs.findbugs.annotations.NonNull",
        "android.support.annotation.NonNull",
        "androidx.annotation.NonNull",
        "androidx.annotation.RecentlyNonNull",
        "org.checkerframework.checker.nullness.qual.NonNull",
        "org.checkerframework.checker.nullness.compatqual.NonNullDecl",
        "org.checkerframework.checker.nullness.compatqual.NonNullType",
        "com.android.annotations.NonNull",
    };

    /** Mutable list of nullable annotation names; returned lists are unmodifiable views. */
    private final List<String> myNullables = new ArrayList<>(Arrays.asList(DEFAULT_NULLABLES));

    /** Mutable list of not-null annotation names; returned lists are unmodifiable views. */
    private final List<String> myNotNulls = new ArrayList<>(Arrays.asList(DEFAULT_NOT_NULLS));

    /** Annotation names the data flow marks as not-null when it instruments code. */
    private List<String> myInstrumentedNotNulls = new ArrayList<>(Collections.singletonList(NOT_NULL));

    /** Annotation written when a nullable type is generated. */
    private String myDefaultNullable = NULLABLE;

    /** Annotation written when a non-null reference type is generated. */
    private String myDefaultNotNull = NOT_NULL;

    /**
     * Creates the manager for {@code project}.
     *
     * @param project the IntelliJ project the service is registered on; services are looked up
     *                through it by the static helpers of the base class
     */
    public NullableNotNullManagerImpl(@NotNull Project project) {
        super(project);
    }

    /** @return whether {@code name} may appear only on type uses rather than on declarations. */
    @Override
    public boolean isTypeUseAnnotationLocationRestricted(@NotNull String name) {
        return false;
    }

    /**
     * Reports whether a local variable may carry the annotation.
     *
     * @param name the annotation's qualified name
     * @return always {@code true}, because no annotation package in the bundled platform
     * restricts local annotations
     */
    @Override
    public boolean canAnnotateLocals(@NotNull String name) {
        return true;
    }

    /**
     * Looks the nullability up by annotation name.
     *
     * @param name the annotation's qualified name
     * @return the nullability the annotation implies, or an empty optional when the name is not
     * a known nullability annotation
     */
    @Override
    public @NotNull Optional<Nullability> getAnnotationNullability(@NotNull String name) {
        return Optional.ofNullable(getAllNullabilityAnnotationsWithNickNames().getNullability(name));
    }

    /** Replaces the nullable annotation list. */
    @Override
    public void setNotNulls(String @NotNull ... annotations) {
        myNotNulls.clear();
        Collections.addAll(myNotNulls, annotations);
    }

    /** Replaces the not-null annotation list. */
    @Override
    public void setNullables(String @NotNull ... annotations) {
        myNullables.clear();
        Collections.addAll(myNullables, annotations);
    }

    /** @return the annotation used for newly generated nullable types. */
    @Override
    public @NotNull String getDefaultNullable() {
        return myDefaultNullable;
    }

    /** Sets the annotation used for newly generated nullable types. */
    @Override
    public void setDefaultNullable(@NotNull String defaultNullable) {
        myDefaultNullable = defaultNullable;
    }

    /** @return the annotation used for newly generated non-null reference types. */
    @Override
    public @NotNull String getDefaultNotNull() {
        return myDefaultNotNull;
    }

    /** Sets the annotation used for newly generated non-null reference types. */
    @Override
    public void setDefaultNotNull(@NotNull String defaultNotNull) {
        myDefaultNotNull = defaultNotNull;
    }

    /** @return an unmodifiable view of the nullable annotation names. */
    @Override
    public @NotNull List<String> getNullables() {
        return Collections.unmodifiableList(myNullables);
    }

    /** @return an unmodifiable view of the not-null annotation names. */
    @Override
    public @NotNull List<String> getNotNulls() {
        return Collections.unmodifiableList(myNotNulls);
    }

    /** @return an unmodifiable view of the annotations used when instrumenting code. */
    @Override
    public @NotNull List<String> getInstrumentedNotNulls() {
        return Collections.unmodifiableList(myInstrumentedNotNulls);
    }

    /** Replaces the annotations used when instrumenting code. */
    @Override
    public void setInstrumentedNotNulls(@NotNull List<String> names) {
        myInstrumentedNotNulls = new ArrayList<>(names);
    }

    /**
     * Returns the effective nullability of {@code owner}, including annotations inherited from
     * overridden methods and from type-use defaults.
     *
     * The computation delegates to the cached base-class algorithm, which queries
     * {@link #getAllNullabilityAnnotationsWithNickNames()} and {@link #getNullityDefault} for the
     * annotation data this class supplies. Primitive types are never nullable and short-circuit.
     *
     * @param owner the declaration to inspect
     * @return the effective nullability annotation, or {@code null} when none applies
     */
    @Override
    public @Nullable NullabilityAnnotationInfo findEffectiveNullabilityInfo(@NotNull PsiModifierListOwner owner) {
        PsiType type = PsiUtil.getTypeByPsiElement(owner);
        if ((type == null && !(owner instanceof PsiTypeParameter))
                || TypeConversionUtil.isPrimitiveAndNotNull(type)) {
            return null;
        }
        return CachedValuesManager.getCachedValue(owner, () ->
            CachedValueProvider.Result.create(
                doFindEffectiveNullabilityAnnotation(owner),
                PsiModificationTracker.MODIFICATION_COUNT));
    }

    /**
     * Builds the annotation-name to nullability map the base class searches.
     *
     * The map is rebuilt on every call rather than cached: the base class already caches the
     * result of {@link #findEffectiveNullabilityInfo}, so this runs only on a cache miss.
     *
     * @return the data holder over the configured annotation names
     */
    @Override
    protected @NotNull NullabilityAnnotationDataHolder getAllNullabilityAnnotationsWithNickNames() {
        Map<String, Nullability> map = new HashMap<>();
        for (String name : myNotNulls) {
            map.put(name, Nullability.NOT_NULL);
        }
        for (String name : myNullables) {
            map.put(name, Nullability.NULLABLE);
        }
        return new MapDataHolder(map);
    }

    /**
     * Resolves a default qualifier declared on {@code container} (a package, class or module).
     *
     * Only JSR-305 {@code @TypeQualifierDefault} containers are recognised. The package-level
     * class-root check of the IDEA implementation is omitted because
     * {@code ProjectFileIndex} is not part of the bundled platform; it only affects package
     * annotations coming from a different library root.
     *
     * @param container the element whose annotations provide the default
     * @param placeTargetTypes the targets the annotation would be placed on
     * @param owner the element being annotated; unused by this implementation
     * @param superPackage whether the lookup already left the element's own package
     * @return the default nullability, or {@code null} when the container declares none
     */
    @Override
    @Nullable
    NullabilityAnnotationInfo getNullityDefault(@NotNull PsiModifierListOwner container,
                                                @NotNull PsiAnnotation.TargetType[] placeTargetTypes,
                                                PsiElement owner,
                                                boolean superPackage) {
        PsiModifierList modifierList = container.getModifierList();
        if (modifierList == null) {
            return null;
        }
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            NullabilityAnnotationInfo result =
                superPackage ? null : isJsr305Default(annotation, placeTargetTypes);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Reads a JSR-305 {@code @TypeQualifierDefault} container annotation.
     *
     * @param annotation the annotation to inspect
     * @param placeTargetTypes the targets the annotation would be placed on
     * @return the default nullability, or {@code null} when the annotation is not a matching
     * JSR-305 default container
     */
    @Nullable
    private NullabilityAnnotationInfo isJsr305Default(@NotNull PsiAnnotation annotation,
                                                      PsiAnnotation.TargetType @NotNull [] placeTargetTypes) {
        PsiClass declaration = resolveAnnotationType(annotation);
        if (declaration == null) {
            return null;
        }
        PsiAnnotation typeQualifierDefault = AnnotationUtil.findAnnotation(declaration, true, TYPE_QUALIFIER_DEFAULT);
        if (typeQualifierDefault == null) {
            return null;
        }
        Set<PsiAnnotation.TargetType> required = AnnotationTargetUtil
            .extractRequiredAnnotationTargets(typeQualifierDefault.findAttributeValue(null));
        if (required == null || (!required.isEmpty() && Collections.disjoint(required, Arrays.asList(placeTargetTypes)))) {
            return null;
        }
        PsiModifierList modifierList = declaration.getModifierList();
        if (modifierList == null) {
            return null;
        }
        for (PsiAnnotation qualifier : modifierList.getAnnotations()) {
            Nullability nullability = getJsr305QualifierNullability(qualifier);
            if (nullability != null) {
                return new NullabilityAnnotationInfo(annotation, nullability, true);
            }
        }
        return null;
    }

    /**
     * Maps a qualifier annotation to its nullability.
     *
     * @param qualifier the annotation nested inside a {@code @TypeQualifierDefault} container
     * @return the implied nullability, or {@code null} when the qualifier is not a JSR-305
     * nullability annotation
     */
    @Nullable
    private Nullability getJsr305QualifierNullability(@NotNull PsiAnnotation qualifier) {
        String qualifiedName = qualifier.getQualifiedName();
        if (qualifiedName == null || !qualifiedName.startsWith("javax.annotation.")) {
            return null;
        }
        if (qualifiedName.equals(JAVAX_ANNOTATION_NULLABLE) && getNullables().contains(qualifiedName)) {
            return Nullability.NULLABLE;
        }
        if (qualifiedName.equals(JAVAX_ANNOTATION_NONNULL)) {
            return extractNullityFromWhenValue(qualifier);
        }
        return null;
    }

    /**
     * Resolves the annotation class an annotation refers to.
     *
     * @param annotation the annotation to resolve
     * @return the annotation type, or {@code null} when it does not resolve to an annotation class
     */
    @Nullable
    private static PsiClass resolveAnnotationType(@NotNull PsiAnnotation annotation) {
        PsiJavaCodeReferenceElement element = annotation.getNameReferenceElement();
        PsiElement declaration = element == null ? null : element.resolve();
        if (!(declaration instanceof PsiClass) || !((PsiClass) declaration).isAnnotationType()) {
            return null;
        }
        return (PsiClass) declaration;
    }

    /**
     * Reads the {@code when} attribute of a {@code javax.annotation.Nonnull} annotation.
     *
     * @param nonNull the {@code javax.annotation.Nonnull} annotation
     * @return the nullability its {@code when} value implies
     */
    @NotNull
    private static Nullability extractNullityFromWhenValue(@NotNull PsiAnnotation nonNull) {
        PsiAnnotationMemberValue when = nonNull.findAttributeValue("when");
        if (!(when instanceof PsiReferenceExpression)) {
            return Nullability.UNKNOWN;
        }
        String referenceName = ((PsiReferenceExpression) when).getReferenceName();
        if ("ALWAYS".equals(referenceName)) {
            return Nullability.NOT_NULL;
        }
        if ("MAYBE".equals(referenceName) || "NEVER".equals(referenceName)) {
            return Nullability.NULLABLE;
        }
        return Nullability.UNKNOWN;
    }

    /**
     * Immutable annotation-name to nullability map handed to the base class.
     */
    private static final class MapDataHolder implements NullabilityAnnotationDataHolder {

        /** Annotation name to implied nullability. */
        private final Map<String, Nullability> myMap;

        /**
         * Wraps {@code map}.
         *
         * @param map annotation name to implied nullability
         */
        private MapDataHolder(@NotNull Map<String, Nullability> map) {
            myMap = map;
        }

        /** @return every known annotation name. */
        @Override
        public @NotNull Set<String> qualifiedNames() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(myMap.keySet()));
        }

        /**
         * @param annotationName the annotation's qualified name
         * @return the implied nullability, or {@code null} when the name is unknown
         */
        @Override
        public @Nullable Nullability getNullability(String annotationName) {
            return myMap.get(annotationName);
        }
    }
}
