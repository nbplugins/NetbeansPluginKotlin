// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.inline;

import com.intellij.lang.Language;
import com.intellij.lang.refactoring.InlineHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.containers.MultiMap;
import java.util.Collections;
import java.util.Map;

/** Stub: Java-language inline handling not needed in standalone NetBeans mode. */
public final class GenericInlineHandler {
    public static boolean invoke(PsiElement element, Editor editor, InlineHandler handler) { return false; }
    public static InlineHandler.Settings prepareInlineElement(PsiElement element, Editor editor, boolean invokedOnReference) { return null; }

    /** Stub: returns empty inliners map (no Java-language inline handlers in standalone mode). */
    public static Map<Language, InlineHandler.Inliner> initInliners(
            PsiElement element,
            UsageInfo[] usagesIn,
            InlineHandler.Settings settings,
            MultiMap<PsiElement, String> conflicts,
            Language language) {
        return Collections.emptyMap();
    }

    /** Stub: no-op (Java-language reference inlining not supported in standalone mode). */
    public static void inlineReference(UsageInfo usage, PsiElement element, Map<Language, InlineHandler.Inliner> inliners) {}
}
