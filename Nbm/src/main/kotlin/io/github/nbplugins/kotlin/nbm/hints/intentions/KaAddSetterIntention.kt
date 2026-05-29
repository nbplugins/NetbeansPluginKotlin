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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Adds a `set()` accessor to a `var` property that currently has no setter.
 *
 * For a property with an initializer the setter uses `field`:
 * ```kotlin
 * var x: Int = 5
 *     set(value) {
 *         field = value
 *     }
 * ```
 * For a property without an initializer an empty setter body is inserted:
 * ```kotlin
 * var name: String
 *     set(value) {
 *     }
 * ```
 *
 * Applicable only to `var` properties with no setter, that are not local, not abstract,
 * not delegated, not in an interface, not `lateinit`, not `const`, have no `expect` modifier,
 * no `@JvmField` annotation, and have a type reference or an initializer.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddSetterIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findProperty(): KtProperty? =
        PsiTreeUtil.getParentOfType(psi, KtProperty::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val property = findProperty() ?: return false
        if (!property.isVar) return false
        if (property.isLocal) return false
        if (property.hasDelegate()) return false
        if (property.containingClass()?.isInterface() == true) return false
        if (property.containingClassOrObject?.hasModifier(KtTokens.EXPECT_KEYWORD) == true) return false
        if (property.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return false
        if (property.hasModifier(KtTokens.LATEINIT_KEYWORD)) return false
        if (property.hasModifier(KtTokens.CONST_KEYWORD)) return false
        if (property.typeReference == null && !property.hasInitializer()) return false
        if (property.annotationEntries.any { it.shortName?.asString() == "JvmField" }) return false
        return property.setter == null
    }

    override fun getDescription(): String = "Add setter"

    override fun implement() {
        val property = findProperty() ?: return
        val propStart = property.textRange.startOffset

        // Insert after the existing getter (if present) or after the property itself
        val insertAfter = property.getter?.textRange?.endOffset
            ?: property.textRange.endOffset

        val docText = doc.getText(0, propStart)
        val lastNewline = docText.lastIndexOf('\n')
        val indent = if (lastNewline >= 0) docText.substring(lastNewline + 1).takeWhile { it.isWhitespace() } else ""
        val ai = "$indent    "

        val setterText = if (property.hasInitializer()) {
            "\n${ai}set(value) {\n${ai}    field = value\n${ai}}"
        } else {
            "\n${ai}set(value) {\n${ai}}"
        }

        doc.atomicChange {
            insertString(insertAfter, setterText, null)
        }
    }
}
