// Copyright 2026 nbplugins contributors
package com.intellij.psi.impl.source.codeStyle;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * No-op {@link IndentHelper} used in the K2 standalone environment.
 *
 * The real implementation lives in the IntelliJ IDE platform and is absent in our
 * embedded analysis container. PSI mutation paths (e.g. {@code PsiElement.replace()})
 * call {@link CodeEditUtil#saveWhitespacesInfo} which calls
 * {@link IndentHelper#getInstance()}.getIndent(...) on every replaced node; without
 * this service registered, the call throws NPE and quick-fixes that mutate PSI fail.
 *
 * Returning 0 indent is safe — the value is only used by IDE-side reformatting code
 * paths we don't execute, and the document text is taken from {@code KtFile.node.text}
 * after the mutation completes.
 */
public final class NoOpIndentHelper extends IndentHelper {
    @Override
    public int getIndent(@NotNull PsiFile file, @NotNull ASTNode element) {
        return 0;
    }

    @Override
    public int getIndent(@NotNull PsiFile file, @NotNull ASTNode element, boolean includeNonSpace) {
        return 0;
    }
}
