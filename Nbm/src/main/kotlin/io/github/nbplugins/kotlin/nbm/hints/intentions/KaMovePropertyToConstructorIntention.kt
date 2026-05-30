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
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.types.Variance

/**
 * Moves a class-body property into the primary constructor parameter list.
 *
 * Example — when the property's initializer is a reference to an existing constructor parameter:
 * ```kotlin
 * class Foo(x: Int) {
 *     val x: Int = x
 * }
 * ```
 * becomes:
 * ```kotlin
 * class Foo(val x: Int) {
 * }
 * ```
 *
 * When there is no matching constructor parameter, a new parameter is added:
 * ```kotlin
 * class Foo {
 *     val name: String = "default"
 * }
 * ```
 * becomes:
 * ```kotlin
 * class Foo(val name: String = "default") {
 * }
 * ```
 *
 * Applicable when the property is non-local, has no delegate, no accessors, no `lateinit`
 * modifier, and belongs to a non-interface, non-expect class with no secondary constructors
 * and no `actual` primary constructor.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaMovePropertyToConstructorIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findProperty(): KtProperty? =
        PsiTreeUtil.getParentOfType(psi, KtProperty::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val property = findProperty() ?: return false
        return property.isMovableToConstructorByPsi()
    }

    override fun getDescription(): String = "Move to constructor"

    override fun implement() {
        val property = findProperty() ?: return
        if (!property.isMovableToConstructorByPsi()) return

        // Compute K2 info: type text and whether the initializer is a constructor parameter reference
        data class MoveInfo(
            val typeText: String,
            val annotationsText: String?,
            val replacementParamName: String?,  // non-null → replace this constructor param
        )

        val info: MoveInfo? = analyze(kaKtFile) {
            val initializer = property.initializer
            val typeText = property.typeReference?.text
                ?: (property.symbol as? KaPropertySymbol)?.returnType?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                ?: return@analyze null
            val annotationsText = property.modifierList?.annotationEntries
                ?.joinToString(" ") { it.text }
                ?.takeIf { it.isNotBlank() }

            // Check if the initializer is a direct reference to a constructor parameter (no val/var)
            val constructorParam: KtParameter? = if (initializer != null) {
                val symbol = initializer.mainReference?.resolveToSymbol() as? KaValueParameterSymbol
                symbol?.psi as? KtParameter
            } else null

            val isReplaceable = constructorParam != null && constructorParam.getStrictParentOfType<KtClass>() != null

            MoveInfo(
                typeText = typeText,
                annotationsText = annotationsText,
                replacementParamName = if (isReplaceable) constructorParam!!.name else null,
            )
        }
        if (info == null) return

        val containingClass = property.getStrictParentOfType<KtClass>() ?: return
        val paramText = buildParameterText(property, info.typeText, info.annotationsText)

        val propStart = property.textRange.startOffset
        val propEnd = property.textRange.endOffset

        doc.atomicChange {
            val docText = getText(0, length)
            // Compute line boundaries: include leading whitespace and trailing newline
            var lineStart = propStart
            while (lineStart > 0 && docText[lineStart - 1] != '\n') lineStart--
            var lineEnd = propEnd
            if (lineEnd < docText.length && docText[lineEnd] == '\n') lineEnd++

            // Delete property line first (it's in the class body, after the class header).
            // This does not shift constructor parameter offsets since those come before.
            remove(lineStart, lineEnd - lineStart)

            // Now modify the constructor (lower offsets, unaffected by the deletion above).
            when {
                info.replacementParamName != null -> {
                    // Replace the existing constructor parameter with val/var + property modifiers
                    val primaryCtor = containingClass.primaryConstructor ?: return@atomicChange
                    val paramToReplace = primaryCtor.valueParameters
                        .firstOrNull { it.name == info.replacementParamName } ?: return@atomicChange
                    val existingAnnotations = paramToReplace.modifierList?.annotationEntries
                        ?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
                    val defaultValue = paramToReplace.defaultValue?.text
                    val fullParamText = buildParameterText(
                        property, info.typeText, info.annotationsText, existingAnnotations, defaultValue
                    )
                    val paramStart = paramToReplace.textRange.startOffset
                    val paramEnd = paramToReplace.textRange.endOffset
                    remove(paramStart, paramEnd - paramStart)
                    insertString(paramStart, fullParamText, null)
                }
                else -> {
                    val primaryCtor = containingClass.primaryConstructor
                    if (primaryCtor != null) {
                        // Constructor exists — append parameter before the closing ')'
                        val paramList = primaryCtor.valueParameterList ?: return@atomicChange
                        val rpar = paramList.node.findChildByType(KtTokens.RPAR)?.startOffset
                            ?: return@atomicChange
                        val prefix = if (paramList.parameters.isEmpty()) "" else ", "
                        insertString(rpar, "$prefix$paramText", null)
                    } else {
                        // No primary constructor — insert "(paramText)" after the class name
                        val insertAfter = containingClass.typeParameterList?.textRange?.endOffset
                            ?: containingClass.nameIdentifier?.textRange?.endOffset
                            ?: return@atomicChange
                        insertString(insertAfter, "($paramText)", null)
                    }
                }
            }
        }
    }

    /** Inlined from MovePropertyToConstructorUtils — builds constructor parameter text for this property. */
    private fun buildParameterText(
        property: KtProperty,
        typeText: String,
        propertyAnnotationsText: String?,
        constructorParamAnnotationsText: String? = null,
        defaultValueText: String? = property.initializer?.text,
    ): String = buildString {
        constructorParamAnnotationsText?.takeIf { it.isNotBlank() }?.let { append(it); append(' ') }
        propertyAnnotationsText?.takeIf { it.isNotBlank() }?.let { append(it); append(' ') }
        // Modifier keywords (override, private, open, etc.) — not val/var (added explicitly below)
        property.modifierList?.node?.getChildren(null)
            ?.filter { it.elementType is KtModifierKeywordToken }
            ?.joinToString(" ") { it.text }
            ?.takeIf { it.isNotBlank() }?.let { append(it); append(' ') }
        append(property.valOrVarKeyword.text)
        append(' ')
        append(property.name)
        append(": ")
        append(typeText)
        defaultValueText?.let { append(" = $it") }
    }
}

/** Inlined from MovePropertyToConstructorUtils.isMovableToConstructorByPsi(). */
private fun KtProperty.isMovableToConstructorByPsi(): Boolean {
    val parent = getStrictParentOfType<KtClass>() ?: return false
    if (parent.isInterface()) return false
    if (parent.hasModifier(KtTokens.EXPECT_KEYWORD)) return false
    if (parent.secondaryConstructors.isNotEmpty()) return false
    if (parent.primaryConstructor?.hasModifier(KtTokens.ACTUAL_KEYWORD) == true) return false
    return !isLocal &&
        !hasDelegate() &&
        getter == null &&
        setter == null &&
        !hasModifier(KtTokens.LATEINIT_KEYWORD)
}
