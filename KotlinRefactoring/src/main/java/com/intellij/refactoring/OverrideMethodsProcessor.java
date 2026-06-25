// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring;

import com.intellij.psi.PsiElement;
import java.util.Collections;
import java.util.List;

/** Stub: override-attribute propagation not needed in standalone NetBeans mode. */
public interface OverrideMethodsProcessor {
    boolean processOverriders(PsiElement element);

    /** Stub: always returns false (no override removal in standalone mode). */
    default boolean removeOverrideAttribute(PsiElement element) { return false; }

    /** Stub extension point: always returns an empty list. */
    EP EP_NAME = new EP();

    final class EP {
        public List<OverrideMethodsProcessor> getExtensionList() { return Collections.emptyList(); }
        /** Kotlin property accessor alias. */
        public List<OverrideMethodsProcessor> getExtensionList_() { return Collections.emptyList(); }
    }
}
