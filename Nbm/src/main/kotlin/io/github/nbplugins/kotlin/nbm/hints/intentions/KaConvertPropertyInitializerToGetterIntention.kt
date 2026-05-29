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
import org.jetbrains.kotlin.types.Variance

/**
 * Converts a property initializer to a getter expression body.
 *
 * For example:
 * ```kotlin
 * val property = 42
 * ```
 * becomes:
 * ```kotlin
 * val property: Int
 *     get() = 42
 * ```
 *
 * If the property has no explicit type reference, the type is inferred via the K2 Analysis API
 * and inserted. For `var` properties without a setter, a `set(value) { TODO() }` accessor is
 * also added.
 *
 * Applicable when the caret is between the name identifier and the end of the initializer,
 * the property has an initializer but no getter, is not local, not an extension, has no
 * `@JvmField` annotation, and has no `const` modifier.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertPropertyInitializerToGetterIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findProperty(): KtProperty? =
        PsiTreeUtil.getParentOfType(psi, KtProperty::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val property = findProperty() ?: return false
        val initializer = property.initializer ?: return false
        if (property.getter != null) return false
        if (property.receiverTypeReference != null) return false
        if (property.isLocal) return false
        if (property.hasModifier(KtTokens.CONST_KEYWORD)) return false
        if (property.annotationEntries.any { it.shortName?.asString() == "JvmField" }) return false
        val nameStart = property.nameIdentifier?.textRange?.startOffset ?: return false
        return caretOffset in nameStart..initializer.textRange.endOffset
    }

    override fun getDescription(): String = "Convert property initializer to getter"

    override fun implement() {
        val property = findProperty() ?: return
        val initializer = property.initializer ?: return

        // K2: infer type if property has no explicit type reference
        val typeText: String? = if (property.typeReference == null) {
            analyze(kaKtFile) {
                val type = initializer.expressionType ?: return@analyze null
                if (type is KaErrorType) null
                else type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            }
        } else null

        val propStart = property.textRange.startOffset
        val propEnd = property.textRange.endOffset
        val propText = property.text

        // Locate the '=' token to split declaration from initializer
        val equalsNode = property.node.findChildByType(KtTokens.EQ)
        val equalsRelOffset = if (equalsNode != null) {
            equalsNode.startOffset - propStart
        } else {
            initializer.textRange.startOffset - propStart - 1
        }

        // Remove trailing whitespace from the declaration part (before '=')
        var declEnd = equalsRelOffset
        while (declEnd > 0 && propText[declEnd - 1].isWhitespace()) declEnd--

        val typeAnnotation = if (typeText != null) ": $typeText" else ""

        // Determine the indentation of this property line
        val docText = doc.getText(0, propStart)
        val lastNewline = docText.lastIndexOf('\n')
        val indent = if (lastNewline >= 0) docText.substring(lastNewline + 1).takeWhile { it.isWhitespace() } else ""

        val getterLine = "\n${indent}    get() = ${initializer.text}"
        val setterLine = if (property.isVar && property.setter == null)
            "\n${indent}    set(value) {\n${indent}        TODO(\"Not yet implemented\")\n${indent}    }" else ""

        val newText = "${propText.substring(0, declEnd)}$typeAnnotation$getterLine$setterLine"
        doc.atomicChange {
            remove(propStart, propEnd - propStart)
            insertString(propStart, newText, null)
        }
    }
}
