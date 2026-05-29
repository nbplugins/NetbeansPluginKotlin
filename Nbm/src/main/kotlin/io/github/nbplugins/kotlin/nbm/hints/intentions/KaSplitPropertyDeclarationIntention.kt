@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
/*******************************************************************************
 * Copyright 2000-2022 JetBrains s.r.o.
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
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.types.Variance

/**
 * Splits a local property declaration with an initializer into a separate declaration and assignment.
 *
 * For example:
 * ```kotlin
 * val x = someExpression()
 * ```
 * becomes:
 * ```kotlin
 * val x: T
 * x = someExpression()
 * ```
 *
 * If the property has no explicit type reference, the type is inferred via the K2 Analysis API
 * and inserted. Only applicable to local variables (not class-level properties), and only when
 * the caret is before the `=` sign.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaSplitPropertyDeclarationIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findProperty(): KtProperty? =
        PsiTreeUtil.getParentOfType(psi, KtProperty::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val property = findProperty() ?: return false
        if (!property.isLocal) return false
        if (property.parent is KtWhenExpression) return false
        val initializer = property.initializer ?: return false
        // Only show when caret is before the '=' (not on the initializer itself)
        return caretOffset < initializer.textRange.startOffset
    }

    override fun getDescription(): String = "Split property declaration"

    override fun implement() {
        val property = findProperty() ?: return
        val initializer = property.initializer ?: return
        val propName = property.name ?: return

        // K2: infer type if property has no explicit type reference
        val typeText: String? = if (property.typeReference == null) {
            analyze(kaKtFile) {
                val type = initializer.expressionType ?: return@analyze null
                if (type is KaErrorType) null
                else type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.OUT_VARIANCE)
            }
        } else null

        val propStart = property.textRange.startOffset
        val propEnd = property.textRange.endOffset
        val propText = property.text

        // Find offset of the '=' token to determine the declaration portion
        val equalsNode = property.node.findChildByType(KtTokens.EQ)
        val equalsRelOffset = if (equalsNode != null) {
            equalsNode.startOffset - propStart
        } else {
            initializer.textRange.startOffset - propStart - 1
        }

        // Trim trailing whitespace from the declaration portion (before '=')
        var declEnd = equalsRelOffset
        while (declEnd > 0 && propText[declEnd - 1].isWhitespace()) declEnd--

        val typeAnnotation = if (typeText != null) ": $typeText" else ""
        val declPart = "${propText.substring(0, declEnd)}$typeAnnotation"

        // Determine indentation from the property line
        val docText = doc.getText(0, propStart)
        val lastNewline = docText.lastIndexOf('\n')
        val indent = if (lastNewline >= 0) docText.substring(lastNewline + 1).takeWhile { it.isWhitespace() } else ""

        val assignment = "$propName = ${initializer.text}"
        doc.atomicChange {
            remove(propStart, propEnd - propStart)
            insertString(propStart, "$declPart\n$indent$assignment", null)
        }
    }
}
