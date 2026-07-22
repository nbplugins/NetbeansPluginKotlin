/* Copyright 2000-2025 JetBrains s.r.o. and contributors. Copyright 2026 nbplugins contributors. */
package com.intellij.refactoring.util.classMembers;

import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiReferenceList;
import com.intellij.refactoring.classMembers.MemberInfoBase;

/** Standalone-compatible upstream member descriptor consumed by Kotlin Pull Up. */
public class MemberInfo extends MemberInfoBase<PsiMember> {
  private final PsiReferenceList sourceReferenceList;

  public MemberInfo(PsiMember member, boolean isSuperClass, PsiReferenceList sourceReferenceList) {
    super(member);
    this.sourceReferenceList = sourceReferenceList;
    this.overrides = isSuperClass ? Boolean.TRUE : null;
  }

  public PsiReferenceList getSourceReferenceList() { return sourceReferenceList; }
}
