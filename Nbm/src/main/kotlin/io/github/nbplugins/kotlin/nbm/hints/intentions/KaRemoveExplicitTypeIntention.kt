/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtDeclarationWithInitializer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.RemoveExplicitTypeIntention].
 *
 * Offers "Remove explicit type specification" when the declaration's type is redundant:
 * - [KtProperty] with an initializer
 * - [KtNamedFunction] with expression body and explicit return type
 * - Loop [KtParameter]
 *
 * Uses K2 [org.jetbrains.kotlin.analysis.api.types.KaType.isUnitType] instead of
 * [org.jetbrains.kotlin.resolve.BindingContext] for the block-body Unit check.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaRemoveExplicitTypeIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean {
        val element = psi.getNonStrictParentOfType(KtCallableDeclaration::class.java) ?: return false

        if (element.containingFile is KtCodeFragment) return false
        if (element.typeReference == null) return false

        val initializer = (element as? KtDeclarationWithInitializer)?.initializer
        if (initializer != null && initializer.textRange.containsOffset(caretOffset)) return false

        return when (element) {
            is KtProperty -> {
                // Require a non-null initializer (null literal can't have its type inferred).
                initializer != null && initializer.text.trim() != "null"
            }
            is KtNamedFunction -> {
                if (element.hasBlockBody()) {
                    // Block-body: only remove explicit Unit return type
                    analyze(kaKtFile) { element.returnType.isUnitType }
                } else {
                    initializer != null && initializer.text.trim() != "null"
                }
            }
            is KtParameter -> element.isLoopParameter
            else -> false
        }
    }

    override fun getDescription(): String = "Remove explicit type specification"

    override fun implement() {
        val element = psi.getNonStrictParentOfType(KtCallableDeclaration::class.java) ?: return
        // Use the colon token as the anchor for removal so that `: Type` is removed precisely.
        val colon = element.colon ?: return
        val typeRef = element.typeReference ?: return
        val colonStart = colon.textRange.startOffset
        val typeEnd = typeRef.textRange.endOffset
        // Guard against stale PSI: verify the document character at colonStart is actually ':'
        if (colonStart >= doc.length || doc.getText(colonStart, 1) != ":") return
        doc.remove(colonStart, typeEnd - colonStart)
    }
}
