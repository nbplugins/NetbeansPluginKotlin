// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.multiverse;

import com.intellij.psi.FileViewProvider;

/**
 * No-op stub for {@link CodeInsightContextManager} registered in the K2 standalone project
 * environment.
 *
 * <p>The binary version of {@link CodeInsightContextManager} (from
 * {@code kotlin-compiler-ir-for-ide:2.3.21}) declares three abstract methods:
 * {@code isSharedSourceSupportEnabled()}, {@code getCodeInsightContext(FileViewProvider)}, and
 * {@code getOrSetContext(FileViewProvider, CodeInsightContext)}.  Without this stub registered
 * as a project service, {@code PsiPackageImpl.getCachedClassesByName} throws
 * {@link IllegalStateException} (cannot find service) while resolving Java types during K2 FIR
 * body resolution, which prevents semantic highlighting from producing any results.
 *
 * <p>Returning {@code false} from {@code isSharedSourceSupportEnabled()} disables multiverse
 * processing in all callers, which is correct for the standalone analysis environment.
 *
 * <p>Written in Java (not Kotlin) so that the Java compiler checks the bytecode-level interface
 * (3 methods) rather than the Kotlin metadata that describes a newer version of the interface.
 */
public class CodeInsightContextManagerStub implements CodeInsightContextManager {

    private static final CodeInsightContext STUB_CONTEXT = new CodeInsightContext() {
        @Override
        public String toString() { return "StubContext"; }
    };

    @Override
    public boolean isSharedSourceSupportEnabled() {
        return false;
    }

    @Override
    public CodeInsightContext getCodeInsightContext(FileViewProvider fileViewProvider) {
        return STUB_CONTEXT;
    }

    @Override
    public CodeInsightContext getOrSetContext(FileViewProvider fileViewProvider, CodeInsightContext context) {
        return context;
    }
}
