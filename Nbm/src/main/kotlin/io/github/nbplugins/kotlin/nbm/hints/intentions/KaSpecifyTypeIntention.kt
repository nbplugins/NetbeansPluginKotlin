@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
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
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import io.github.nbplugins.kotlin.nbm.hints.intentions.getAnchorK2
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.types.Variance
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.SpecifyTypeIntention].
 *
 * Offers "Specify type explicitly" when the caret is on a callable declaration that has no
 * explicit type annotation.  Uses K2 [KaType] instead of [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaSpecifyTypeIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var displayString = ""

    override fun isApplicable(caretOffset: Int): Boolean {
        val element = psi.getNonStrictParentOfType(KtCallableDeclaration::class.java) ?: return false

        if (element.containingFile is KtCodeFragment) return false
        if (element is KtFunctionLiteral) return false
        if (element is KtConstructor<*>) return false
        if (element.typeReference != null) return false
        if (element is KtNamedFunction && element.hasBlockBody()) return false

        val initializer = (element as? org.jetbrains.kotlin.psi.KtDeclarationWithInitializer)?.initializer
        if (initializer != null && initializer.textRange.containsOffset(caretOffset)) return false

        val isNotError = runCatching {
            analyze(kaKtFile) {
                val returnType = element.returnType
                returnType !is KaErrorType
            }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logInfo("KaSpecifyTypeIntention: K2 analyze failed for ${element.name}: $e")
            false
        }
        if (!isNotError) {
            KotlinLogger.INSTANCE.logInfo("KaSpecifyTypeIntention: skipped ${element.javaClass.simpleName} '${element.name}' — error type or K2 failure")
            return false
        }

        displayString = if (element is KtNamedFunction) "Specify return type explicitly" else "Specify type explicitly"
        KotlinLogger.INSTANCE.logInfo("KaSpecifyTypeIntention: applicable for ${element.javaClass.simpleName} '${element.name}' typeRef=${element.typeReference?.text} blockBody=${if (element is KtNamedFunction) element.hasBlockBody() else false}")
        return true
    }

    override fun getDescription(): String = displayString

    override fun implement() {
        val element = psi.getNonStrictParentOfType(KtCallableDeclaration::class.java) ?: return
        val anchor = getAnchorK2(element) ?: return

        val typeText = analyze(kaKtFile) {
            val returnType = element.returnType
            if (returnType is KaErrorType) null
            else returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        } ?: return

        doc.insertString(anchor.textRange.endOffset, ": $typeText", null)
    }
}
