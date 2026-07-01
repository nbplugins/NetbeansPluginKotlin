/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package com.intellij.refactoring.util;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.usageView.UsageInfo;

/**
 * Linkage stub of IntelliJ's {@code com.intellij.refactoring.util.MoveRenameUsageInfo}.
 *
 * <p>This class is the superclass of {@code K2MoveRenameUsageInfo} (the IDEA Copy Declaration
 * retargeting engine ported into {@code KotlinRefactoring} for E9.19).  The <strong>real</strong>
 * platform class lives in the bundled {@code analysis:253} JAR, and {@code KotlinRefactoring}
 * compiles against it.  However, the real {@code init(...)} eagerly calls
 * {@code PsiDocumentManager.getDocument(file)} and asserts
 * {@code referenceEndOffset <= document.getTextLength()}.
 *
 * <p>In {@code StandaloneAnalysisAPISession} (MockProject) there is <em>no PSI-document
 * synchronizer</em> — the session runs non-physical, event-free PSI and never maintains a live
 * {@link com.intellij.openapi.editor.Document} for mutated PSI (physical PSI events throw in the
 * MockComponentManager).  When the engine constructs a usage for a reference inside a
 * freshly-copied declaration, the real {@code init} either trips a "Document/PSI mismatch" or the
 * length assertion against a stale document.
 *
 * <p>This stub provides the same public ABI ({@code (PsiElement, PsiReference, PsiElement)} and the
 * six-arg ctor, {@link #getReferencedElement()}) but performs <strong>no document access</strong> —
 * it only stores the referenced element and a smart pointer to it.  Because {@code Nbm}'s own
 * classes take classloader precedence over the bundled JARs (see
 * <a href="../../../../../../../../docs/stubs.md">docs/stubs.md</a>), this shadows the platform
 * copy at runtime so the engine's {@code retarget} / {@code shortenReferences} pipeline runs without
 * the document dependency.
 *
 * <p>Only Copy Declaration uses this class; nothing in the plugin relies on the document-derived
 * fields the real {@code init} would compute.
 */
public class MoveRenameUsageInfo extends UsageInfo {
    private PsiElement myReferencedElement;
    private SmartPsiElementPointer<? extends PsiElement> myReferencedElementPointer;

    /**
     * Mirrors the platform three-argument constructor used by {@code K2MoveRenameUsageInfo.Source}.
     *
     * @param element          the usage element
     * @param reference        the (unused here) reference; accepted for ABI compatibility
     * @param referencedElement the element this usage points at
     */
    public MoveRenameUsageInfo(PsiElement element, PsiReference reference, PsiElement referencedElement) {
        super(element);
        storeReferenced(referencedElement);
    }

    /**
     * Mirrors the platform six-argument constructor (offset-based) for ABI compatibility.
     *
     * @param element           the usage element
     * @param reference         the (unused here) reference
     * @param startOffset       reference start offset within {@code element}
     * @param endOffset         reference end offset within {@code element}
     * @param referencedElement the element this usage points at
     * @param nonCodeUsage      whether this is a non-code (comment/string) usage
     */
    public MoveRenameUsageInfo(PsiElement element,
                               PsiReference reference,
                               int startOffset,
                               int endOffset,
                               PsiElement referencedElement,
                               boolean nonCodeUsage) {
        super(element, startOffset, endOffset, nonCodeUsage);
        storeReferenced(referencedElement);
    }

    /** Records the referenced element and a smart pointer to it (no document access). */
    private void storeReferenced(PsiElement referencedElement) {
        myReferencedElement = referencedElement;
        if (referencedElement != null) {
            myReferencedElementPointer =
                SmartPointerManager.getInstance(referencedElement.getProject())
                    .createSmartPsiElementPointer(referencedElement);
        }
    }

    /**
     * @return the (possibly restored) element this usage references, matching the platform API
     */
    public PsiElement getReferencedElement() {
        return myReferencedElementPointer != null ? myReferencedElementPointer.getElement() : myReferencedElement;
    }
}
