/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors.
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
 */
package com.intellij.refactoring.memberPullUp;

import com.intellij.lang.Language;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.refactoring.util.DocCommentPolicy;
import com.intellij.refactoring.util.classMembers.MemberInfo;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Standalone extraction of IDEA's {@code PullUpProcessor.moveMembersToBase()} path.
 *
 * IDEA normally wraps this class in {@code BaseRefactoringProcessor}, usage-view, listener,
 * duplicate-search, and project-index lifecycle code. NetBeans invokes it only after the real K2
 * Extract Super engine has created a Kotlin target class, so this copied processor keeps the
 * semantic member-move algorithm and removes only those unavailable IDE lifecycle integrations.
 */
public final class PullUpProcessor implements PullUpData {
    private final @NotNull PsiClass mySourceClass;
    private final PsiClass myTargetSuperClass;
    private final MemberInfo[] myMembersToMove;
    private final DocCommentPolicy myJavaDocPolicy;
    private Set<PsiMember> myMembersAfterMove;
    private Set<PsiMember> myMovedMembers;
    private final Map<Language, PullUpHelper<MemberInfo>> myProcessors = new HashMap<>();

    /**
     * Creates a processor for the source class, extracted target class, and selected members.
     *
     * @param sourceClass source Kotlin light class.
     * @param targetSuperClass extracted Kotlin light class.
     * @param membersToMove selected member descriptors.
     * @param javaDocPolicy documentation policy retained for the upstream data contract.
     */
    public PullUpProcessor(@NotNull PsiClass sourceClass, PsiClass targetSuperClass,
                           MemberInfo[] membersToMove, DocCommentPolicy javaDocPolicy) {
        mySourceClass = sourceClass;
        myTargetSuperClass = targetSuperClass;
        myMembersToMove = membersToMove;
        myJavaDocPolicy = javaDocPolicy;
    }

    /** Runs IDEA's member-move, post-processing, and field-initialization algorithm. */
    public void moveMembersToBase() {
        myMovedMembers = new LinkedHashSet<>();
        myMembersAfterMove = new LinkedHashSet<>();

        for (MemberInfo info : myMembersToMove) {
            myMovedMembers.add(info.getMember());
        }

        PsiSubstitutor substitutor = upDownSuperClassSubstitutor();
        for (MemberInfo info : myMembersToMove) {
            PullUpHelper<MemberInfo> processor = getProcessor(info);
            if (processor == null) {
                throw new IllegalStateException("No Pull Up helper for " + info.getMember());
            }
            if (!(info.getMember() instanceof PsiClass) || info.getOverrides() == null) {
                processor.setCorrectVisibility(info);
                processor.encodeContextInfo(info);
            }
            processor.move(info, substitutor);
        }

        for (PsiMember member : myMembersAfterMove) {
            PullUpHelper<MemberInfo> processor = getProcessor(member);
            if (processor == null) {
                throw new IllegalStateException("No Pull Up helper for moved " + member);
            }
            processor.postProcessMember(member);
        }

        moveFieldInitializations();
    }

    private PullUpHelper<MemberInfo> getProcessor(@NotNull MemberInfo info) {
        return getProcessor(info.getSourceReferenceList() != null
                ? info.getSourceReferenceList().getLanguage()
                : info.getMember().getLanguage());
    }

    private PullUpHelper<MemberInfo> getProcessor(@NotNull PsiMember member) {
        return getProcessor(member.getLanguage());
    }

    @SuppressWarnings("unchecked")
    private PullUpHelper<MemberInfo> getProcessor(Language language) {
        PullUpHelper<MemberInfo> helper = myProcessors.get(language);
        if (helper != null) {
            return helper;
        }
        PullUpHelperFactory factory = PullUpHelper.INSTANCE.forLanguage(language);
        if (factory == null) {
            return null;
        }
        helper = factory.createPullUpHelper(this);
        myProcessors.put(language, helper);
        return helper;
    }

    private PsiSubstitutor upDownSuperClassSubstitutor() {
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
        for (PsiTypeParameter parameter : PsiUtil.typeParametersIterable(mySourceClass)) {
            substitutor = substitutor.put(parameter, null);
        }
        Map<PsiTypeParameter, PsiType> substitutionMap =
                TypeConversionUtil.getSuperClassSubstitutor(myTargetSuperClass, mySourceClass,
                        PsiSubstitutor.EMPTY).getSubstitutionMap();
        for (PsiTypeParameter parameter : substitutionMap.keySet()) {
            PsiType type = substitutionMap.get(parameter);
            PsiClass resolvedClass = PsiUtil.resolveClassInType(type);
            if (resolvedClass instanceof PsiTypeParameter) {
                substitutor = substitutor.put((PsiTypeParameter) resolvedClass,
                        JavaPsiFacade.getElementFactory(myProject()).createType(parameter));
            }
        }
        return substitutor;
    }

    private void moveFieldInitializations() {
        LinkedHashSet<com.intellij.psi.PsiField> movedFields = new LinkedHashSet<>();
        for (PsiMember member : myMembersAfterMove) {
            if (member instanceof com.intellij.psi.PsiField) {
                movedFields.add((com.intellij.psi.PsiField) member);
            }
        }
        if (!movedFields.isEmpty()) {
            PullUpHelper<MemberInfo> processor = getProcessor(myTargetSuperClass);
            if (processor == null) {
                throw new IllegalStateException("No Pull Up helper for target " + myTargetSuperClass);
            }
            processor.moveFieldInitializations(movedFields);
        }
    }

    /** @return source class supplied to the processor. */
    @Override public PsiClass getSourceClass() { return mySourceClass; }
    /** @return extracted target class supplied to the processor. */
    @Override public PsiClass getTargetClass() { return myTargetSuperClass; }
    /** @return documentation policy supplied to the processor. */
    @Override public DocCommentPolicy getDocCommentPolicy() { return myJavaDocPolicy; }
    /** @return selected members being moved. */
    @Override public Set<PsiMember> getMembersToMove() { return myMovedMembers; }
    /** @return members created by the K2 helper. */
    @Override public Set<PsiMember> getMovedMembers() { return myMembersAfterMove; }
    /** @return IntelliJ project owning the source PSI. */
    @Override public Project getProject() { return myProject(); }

    private Project myProject() { return mySourceClass.getProject(); }
}
