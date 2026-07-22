/* Copyright 2000-2025 JetBrains s.r.o. and contributors. Copyright 2026 nbplugins contributors. */
package com.intellij.refactoring.util;

/** Standalone-compatible documentation policy value retained by the Pull Up data contract. */
public final class DocCommentPolicy {
  private final int policy;
  public DocCommentPolicy(int policy) { this.policy = policy; }
  public int getPolicy() { return policy; }
}
