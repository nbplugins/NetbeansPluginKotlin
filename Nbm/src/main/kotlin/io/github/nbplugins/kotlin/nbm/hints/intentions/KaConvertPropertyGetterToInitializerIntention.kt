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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClass

/**
 * Converts a property getter with a single expression body to a property initializer.
 *
 * For example:
 * ```kotlin
 * val foo: Int
 *     get() = 42
 * ```
 * becomes:
 * ```kotlin
 * val foo: Int = 42
 * ```
 *
 * Applicable when the getter has a single expression body, the property has no initializer,
 * is not an extension property, not declared in an interface, and has no `expect` modifier.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertPropertyGetterToInitializerIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findAccessor(): KtPropertyAccessor? =
        PsiTreeUtil.getParentOfType(psi, KtPropertyAccessor::class.java, false)
            ?.takeIf { it.isGetter && it.singleExpression() != null }

    override fun isApplicable(caretOffset: Int): Boolean {
        val accessor = findAccessor() ?: return false
        val property = accessor.parent as? KtProperty ?: return false
        return !property.hasInitializer() &&
            property.receiverTypeReference == null &&
            property.containingClass()?.isInterface() != true &&
            accessor.modifierList?.hasModifier(KtTokens.EXPECT_KEYWORD) != true &&
            property.containingClass()?.hasModifier(KtTokens.EXPECT_KEYWORD) != true
    }

    override fun getDescription(): String = "Convert property getter to initializer"

    override fun implement() {
        val accessor = findAccessor() ?: return
        val property = accessor.parent as? KtProperty ?: return
        val expr = accessor.singleExpression() ?: return

        val propStart = property.textRange.startOffset
        val propEnd = property.textRange.endOffset
        val propText = property.text

        // Strip everything from the last non-whitespace char before the accessor up to end of property,
        // then append " = <expr>"
        val accessorRelOffset = accessor.textRange.startOffset - propStart
        var prefixEnd = accessorRelOffset
        while (prefixEnd > 0 && propText[prefixEnd - 1].isWhitespace()) prefixEnd--

        val newText = "${propText.substring(0, prefixEnd)} = ${expr.text}"
        doc.atomicChange {
            remove(propStart, propEnd - propStart)
            insertString(propStart, newText, null)
        }
    }
}

/** Returns the single body expression of a getter (handles `get() = expr` and `get() { return expr }`). */
private fun KtPropertyAccessor.singleExpression(): KtExpression? = when (val body = bodyExpression) {
    is KtBlockExpression -> (body.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
    else -> body
}
