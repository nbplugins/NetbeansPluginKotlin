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
import io.github.nbplugins.kotlin.nbm.reformatting.format
import javax.swing.text.Document
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Adds a `get()` accessor to a property that currently has no getter.
 *
 * For a property with an initializer the getter uses `field`:
 * ```kotlin
 * var x: Int = 5
 *     get() = field
 * ```
 * For a property without an initializer a `TODO()` body is inserted:
 * ```kotlin
 * val name: String
 *     get() {
 *         TODO("Not yet implemented")
 *     }
 * ```
 *
 * Applicable when the property has no getter, is not local, not abstract, not delegated,
 * not in an interface, not `lateinit`, not `const`, has no `expect` modifier, no `@JvmField`
 * annotation, and has a type reference or an initializer.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddGetterIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findProperty(): KtProperty? =
        PsiTreeUtil.getParentOfType(psi, KtProperty::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val property = findProperty() ?: return false
        if (property.isLocal) return false
        if (property.hasDelegate()) return false
        if (property.containingClass()?.isInterface() == true) return false
        if (property.containingClassOrObject?.hasModifier(KtTokens.EXPECT_KEYWORD) == true) return false
        if (property.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return false
        if (property.hasModifier(KtTokens.LATEINIT_KEYWORD)) return false
        if (property.hasModifier(KtTokens.CONST_KEYWORD)) return false
        if (property.typeReference == null && !property.hasInitializer()) return false
        if (property.annotationEntries.any { it.shortName?.asString() == "JvmField" }) return false
        return property.getter == null
    }

    override fun getDescription(): String = "Add getter"

    override fun implement() {
        val property = findProperty() ?: return
        val propStart = property.textRange.startOffset
        val propEnd = property.textRange.endOffset
        val propText = property.text

        val fullDocText = doc.getText(0, doc.length)
        val docTextBefore = fullDocText.substring(0, propStart)
        val lastNewline = docTextBefore.lastIndexOf('\n')
        val indent = if (lastNewline >= 0) docTextBefore.substring(lastNewline + 1).takeWhile { it.isWhitespace() } else ""
        val step = detectIndentStep(fullDocText, propStart)
        val ai = "$indent$step"

        val getterText = if (property.hasInitializer()) {
            "\n${ai}get() = field"
        } else {
            "\n${ai}get() {\n${ai}${step}TODO(\"Not yet implemented\")\n${ai}}"
        }

        doc.atomicChange {
            remove(propStart, propEnd - propStart)
            insertString(propStart, "$propText$getterText", null)
        }
        var lineStart = propStart
        while (lineStart > 0 && fullDocText[lineStart - 1] != '\n') lineStart--
        format(doc, propStart, lineStart, propStart + propText.length + getterText.length)
    }
}
