/* Copyright 2000-2025 JetBrains s.r.o. and contributors. Copyright 2026 nbplugins contributors. */
package com.intellij.refactoring.classMembers;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;

/** Standalone-compatible upstream member descriptor base for Kotlin Pull Up. */
public abstract class MemberInfoBase<T extends PsiElement> {
  private SmartPsiElementPointer<T> member;
  protected boolean isStatic;
  protected String displayName;
  private boolean checked;
  protected Boolean overrides;
  private boolean toAbstract;

  public MemberInfoBase(T member) { updateMember(member); }
  public boolean isStatic() { return isStatic; }
  public String getDisplayName() { return displayName; }
  public boolean isChecked() { return checked; }
  public void setChecked(boolean checked) { this.checked = checked; }
  public Boolean getOverrides() { return overrides; }
  public T getMember() { return member.getElement(); }
  public void updateMember(T element) {
    member = SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
  }
  public boolean isToAbstract() { return toAbstract; }
  public void setToAbstract(boolean toAbstract) { this.toAbstract = toAbstract; }
}
