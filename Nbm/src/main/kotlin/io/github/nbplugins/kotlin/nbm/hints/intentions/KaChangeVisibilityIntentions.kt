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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.codeinsight.utils.canBeInternal
import org.jetbrains.kotlin.idea.codeinsight.utils.canBePrivate
import org.jetbrains.kotlin.idea.codeinsight.utils.canBeProtected
import org.jetbrains.kotlin.idea.codeinsight.utils.canBePublic
import org.jetbrains.kotlin.idea.codeinsight.utils.toKeywordToken
import org.jetbrains.kotlin.idea.codeinsight.utils.toVisibility
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierType

/**
 * Returns the implicit (default) visibility keyword for this declaration.
 *
 * Most declarations default to `public` ([KtTokens.DEFAULT_VISIBILITY_KEYWORD]). Constructors
 * of `enum` and `sealed` classes have different defaults, and overriding declarations inherit
 * the maximum visibility of their overridden symbols (requires K2 analysis).
 *
 * @return the implicit visibility keyword token, or null if K2 resolution returns null for overrides
 */
@OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
private fun KtDeclaration.implicitVisibility(): KtModifierKeywordToken? {
    return when {
        this is KtPropertyAccessor && isSetter && property.hasModifier(KtTokens.OVERRIDE_KEYWORD) -> {
            analyze(property) {
                (property.symbol as? KaPropertySymbol)
                    ?.allOverriddenSymbols
                    ?.forEach { overriddenSymbol ->
                        val vis = (overriddenSymbol as? KaPropertySymbol)?.setter
                            ?.compilerVisibility?.toKeywordToken()
                        if (vis != null) return vis
                    }
            }
            KtTokens.DEFAULT_VISIBILITY_KEYWORD
        }

        this is KtConstructor<*> -> {
            val klass = containingClassOrObject as? KtClass ?: return KtTokens.DEFAULT_VISIBILITY_KEYWORD
            when {
                klass.isEnum() -> KtTokens.PRIVATE_KEYWORD
                klass.isSealed() ->
                    if (klass.languageVersionSettings.supportsFeature(LanguageFeature.SealedInterfaces))
                        KtTokens.PROTECTED_KEYWORD
                    else
                        KtTokens.PRIVATE_KEYWORD
                else -> KtTokens.DEFAULT_VISIBILITY_KEYWORD
            }
        }

        hasModifier(KtTokens.OVERRIDE_KEYWORD) -> {
            analyze(this) {
                (this@implicitVisibility.symbol as? KaCallableSymbol)
                    ?.allOverriddenSymbols
                    ?.mapNotNull { (it as? KaDeclarationSymbol)?.compilerVisibility }
                    ?.maxWithOrNull { v1, v2 -> Visibilities.compare(v1, v2) ?: -1 }
                    ?.toKeywordToken()
            }
        }

        else -> KtTokens.DEFAULT_VISIBILITY_KEYWORD
    }
}

/**
 * Base for the four "Make public/private/protected/internal" intentions.
 *
 * Each subclass supplies [modifier] and [description]. The [isApplicable] check enforces:
 * - PSI guard: declaration does not already carry the target visibility modifier and passes [canBeX]
 * - K2 guard: the effective compiler visibility differs from the target
 * - K2 override guard: for override declarations the target visibility must not be less visible
 *   than any overridden symbol's visibility
 *
 * The [implement] method replaces or removes the existing visibility keyword in the document,
 * honouring implicit-visibility rules (e.g. removes `public` when `public` is the default).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 * @param modifier the target visibility keyword
 * @param description user-visible action name
 */
abstract class KaChangeVisibilityBaseIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
    protected val modifier: KtModifierKeywordToken,
    private val description: String,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var cachedDecl: KtDeclaration? = null

    /**
     * Walks up from [psi] to the nearest enclosing [KtDeclaration] that can have a visibility
     * modifier. Stops at function-body boundaries ([KtBlockExpression]) so that intentions are
     * not offered when the caret is inside a function body and would incorrectly target the
     * enclosing function.
     */
    protected fun findDeclaration(): KtDeclaration? {
        var elem: PsiElement? = psi
        while (elem != null && elem !is KtFile) {
            if (elem is KtBlockExpression) return null
            if (elem is KtDeclaration && elem !is KtFunctionLiteral) return elem
            elem = elem.parent
        }
        return null
    }

    /** Returns true if the receiver's PSI constraints allow the target visibility. */
    protected abstract fun KtDeclaration.canBeTarget(): Boolean

    override fun isApplicable(caretOffset: Int): Boolean {
        val decl = findDeclaration() ?: return false
        if (decl.modifierList?.hasModifier(modifier) == true) return false
        if (!decl.canBeTarget()) return false
        if (KtPsiUtil.isLocal((decl as? KtPropertyAccessor)?.property ?: decl)) return false

        val targetVisibility = modifier.toVisibility()

        val applicable = analyze(kaKtFile) {
            val symbol = decl.symbol as? KaDeclarationSymbol ?: return false
            // Already effectively the target visibility
            if (symbol.compilerVisibility == targetVisibility) return false

            // Override constraint: cannot reduce visibility below any overridden symbol
            if (decl.modifierList?.hasModifier(KtTokens.OVERRIDE_KEYWORD) == true) {
                val callable = symbol as? KaCallableSymbol ?: return false
                val violated = callable.allOverriddenSymbols
                    .map { (it as? KaDeclarationSymbol)?.compilerVisibility?.compareTo(targetVisibility) }
                    .any { it == null || it > 0 }
                if (violated) return false
            }

            // KtPropertyAccessor setter: getter visibility cannot be changed; setter visibility
            // must be at most as visible as the property visibility
            if (decl is KtPropertyAccessor) {
                if (decl.isGetter) return false
                if (targetVisibility == Visibilities.Public) {
                    if (decl.modifierList?.visibilityModifierType()?.value == null) return false
                } else {
                    val propSymbol = decl.property.symbol as? KaDeclarationSymbol ?: return false
                    val propVisibility = propSymbol.compilerVisibility
                    if (propVisibility == targetVisibility) return false
                    val cmp = targetVisibility.compareTo(propVisibility)
                    if (cmp == null || cmp > 0) return false
                }
            }

            true
        }
        if (!applicable) return false
        cachedDecl = decl
        return true
    }

    override fun getDescription(): String = description

    override fun implement() {
        val decl = cachedDecl ?: findDeclaration() ?: return
        val implicit = decl.implicitVisibility()
        val modifierList = decl.modifierList
        val currentToken = modifierList?.visibilityModifierType()
        val currentElement = currentToken?.let { modifierList.getModifier(it) }

        when {
            currentElement != null && modifier == implicit -> {
                // Remove explicit visibility modifier (target is the implicit default).
                val start = currentElement.textRange.startOffset
                val len = currentElement.textRange.length
                val removeLen = if (start + len < doc.length && doc.getText(start + len, 1) == " ") len + 1 else len
                doc.atomicChange { remove(start, removeLen) }
            }
            currentElement != null -> {
                // Replace the existing visibility keyword in place.
                val start = currentElement.textRange.startOffset
                doc.atomicChange {
                    remove(start, currentElement.textRange.length)
                    insertString(start, modifier.value, null)
                }
            }
            modifier != implicit -> {
                // No existing visibility modifier; insert before the modifier list or declaration keyword.
                val insertAt = modifierList?.textRange?.startOffset ?: decl.textRange.startOffset
                doc.atomicChange { insertString(insertAt, "${modifier.value} ", null) }
            }
            // else: target == implicit, no current modifier → nothing to do
        }
    }
}

/**
 * Offers "Make public" on any [KtDeclaration] that is not already effectively public and
 * satisfies [canBePublic].
 *
 * When `public` is the implicit default visibility for the declaration, applying this intention
 * removes the explicit visibility modifier rather than adding `public`.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaChangeVisibilityToPublicIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaChangeVisibilityBaseIntention(doc, kaKtFile, psi, KtTokens.PUBLIC_KEYWORD, "Make public") {
    override fun KtDeclaration.canBeTarget(): Boolean = canBePublic()
}

/**
 * Offers "Make private" on any [KtDeclaration] that is not already `private` and satisfies
 * [canBePrivate].
 *
 * The override-visibility constraint prevents reducing visibility below that of any overridden
 * symbol.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaChangeVisibilityToPrivateIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaChangeVisibilityBaseIntention(doc, kaKtFile, psi, KtTokens.PRIVATE_KEYWORD, "Make private") {
    override fun KtDeclaration.canBeTarget(): Boolean = canBePrivate()
}

/**
 * Offers "Make protected" on any [KtDeclaration] that is not already `protected` and satisfies
 * [canBeProtected] (i.e., is a member of a non-interface class body).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaChangeVisibilityToProtectedIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaChangeVisibilityBaseIntention(doc, kaKtFile, psi, KtTokens.PROTECTED_KEYWORD, "Make protected") {
    override fun KtDeclaration.canBeTarget(): Boolean = canBeProtected()
}

/**
 * Offers "Make internal" on any [KtDeclaration] that is not already `internal` and satisfies
 * [canBeInternal].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaChangeVisibilityToInternalIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaChangeVisibilityBaseIntention(doc, kaKtFile, psi, KtTokens.INTERNAL_KEYWORD, "Make internal") {
    override fun KtDeclaration.canBeTarget(): Boolean = canBeInternal()
}
