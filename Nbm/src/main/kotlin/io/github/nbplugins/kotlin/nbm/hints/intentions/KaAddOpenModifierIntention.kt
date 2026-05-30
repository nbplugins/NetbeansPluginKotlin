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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Adds the `open` modifier to a final function or property in an open/abstract/sealed/enum class.
 *
 * Applicable when the K2 Analysis API reports the member's modality as [KaSymbolModality.FINAL]
 * and its containing class is open, abstract, sealed, or an enum. Skips members already
 * marked `open`, `abstract`, or `private`.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddOpenModifierIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var cachedDecl: KtCallableDeclaration? = null

    private fun findDeclaration(): KtCallableDeclaration? {
        var elem: PsiElement? = psi
        while (elem != null && elem !is KtFile) {
            if (elem is KtBlockExpression) return null
            if (elem is KtCallableDeclaration && elem !is KtFunctionLiteral) return elem
            elem = elem.parent
        }
        return null
    }

    override fun isApplicable(caretOffset: Int): Boolean {
        val decl = findDeclaration() ?: return false
        if (decl !is KtNamedFunction && decl !is KtProperty) return false
        if (decl.hasModifier(KtTokens.OPEN_KEYWORD)) return false
        if (decl.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return false
        if (decl.hasModifier(KtTokens.PRIVATE_KEYWORD)) return false

        val applicable = analyze(kaKtFile) {
            val elementSymbol = decl.symbol as? KaDeclarationSymbol ?: return false
            if (elementSymbol.modality == KaSymbolModality.OPEN || elementSymbol.modality == KaSymbolModality.ABSTRACT) {
                return false
            }
            val owner = decl.containingClassOrObject ?: return false
            val ownerSymbol = owner.symbol as? KaDeclarationSymbol ?: return false
            owner.hasModifier(KtTokens.ENUM_KEYWORD)
                    || ownerSymbol.modality == KaSymbolModality.OPEN
                    || ownerSymbol.modality == KaSymbolModality.ABSTRACT
                    || ownerSymbol.modality == KaSymbolModality.SEALED
        }
        if (!applicable) return false
        cachedDecl = decl
        return true
    }

    override fun getDescription(): String = "Make 'open'"

    override fun implement() {
        val decl = cachedDecl ?: findDeclaration() ?: return
        val insertAt = decl.modifierList?.textRange?.startOffset ?: decl.textRange.startOffset
        doc.atomicChange { insertString(insertAt, "open ", null) }
    }
}
