package com.intellij.extapi.psi;

import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.impl.source.SubstrateRef;

// Patch: add getGreenStub() missing from bundled intellij-core.jar
// KtAnnotationEntry (kotlin-compiler 1.3.72) calls this; it should return stub without cancellation check
public class StubBasedPsiElementBase<T extends StubElement> extends ASTDelegatePsiElement {
    // Delegated to superclass via patched bytecode; only getGreenStub() is added here
    public T getGreenStub() {
        return getStub();
    }
}
