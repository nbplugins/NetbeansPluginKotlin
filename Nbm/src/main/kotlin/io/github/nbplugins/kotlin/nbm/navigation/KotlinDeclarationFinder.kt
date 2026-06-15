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
package io.github.nbplugins.kotlin.nbm.navigation

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.navigation.getReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.utils.LineEndUtil
import org.jetbrains.kotlin.utils.ProjectUtils
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import org.netbeans.modules.csl.api.DeclarationFinder
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.csl.spi.ParserResult
import javax.swing.text.Document

/**
 * CSL [DeclarationFinder] for Kotlin source files, enabling **Ctrl+B** (Go to Declaration)
 * and **Ctrl+Shift+B** (Go to Source).
 *
 * Both actions are routed here by `CslEditorKit` via
 * `KotlinLanguage.getDeclarationFinder()`. The finder:
 * 1. Resolves the reference expression at the cursor using [KaNavigationUtils.resolveToSourcePsi].
 * 2. Locates the target [FileObject] via [findFileObjectForReferencedElement].
 * 3. Returns a [DeclarationFinder.DeclarationLocation] with a [KotlinElementHandle] for
 *    CSL's navigation and chooser UI.
 *
 * This class belongs to the **controller** layer: it wires K2 model utilities to the CSL API.
 */
class KotlinDeclarationFinder : DeclarationFinder {

    /**
     * Returns the [OffsetRange] of the reference token at [caretOffset] in [doc].
     *
     * CSL uses this range to underline the reference token when the user holds Ctrl. Returns
     * [OffsetRange.NONE] when the offset is not on a reference expression.
     *
     * @param doc the document to scan
     * @param caretOffset the caret position
     * @return the range of the reference token, or [OffsetRange.NONE]
     */
    override fun getReferenceSpan(doc: Document, caretOffset: Int): OffsetRange {
        val psi = getReferenceExpression(doc, caretOffset) ?: return OffsetRange.NONE
        val refExpr = PsiTreeUtil.getNonStrictParentOfType(psi, KtReferenceExpression::class.java)
            ?: return OffsetRange.NONE
        return OffsetRange(refExpr.textRange.startOffset, refExpr.textRange.endOffset)
    }

    /**
     * Resolves the reference at [caretOffset] in [info] to its declaration location.
     *
     * @param info the parser result for the current file
     * @param caretOffset the caret position within the file
     * @return the [DeclarationFinder.DeclarationLocation] of the declaration, or
     *         [DeclarationFinder.DeclarationLocation.NONE] if it cannot be resolved
     */
    override fun findDeclaration(
        info: ParserResult,
        caretOffset: Int
    ): DeclarationFinder.DeclarationLocation = runCatching {
        val kr = info as? KotlinParserResult
            ?: return DeclarationFinder.DeclarationLocation.NONE
        val fo = kr.file
        val doc = ProjectUtils.getDocumentFromFileObject(fo)
            ?: return DeclarationFinder.DeclarationLocation.NONE
        val project = kr.project

        val psi = getReferenceExpression(doc, caretOffset)
            ?: return DeclarationFinder.DeclarationLocation.NONE
        val refExpr = PsiTreeUtil.getNonStrictParentOfType(psi, KtReferenceExpression::class.java)
            ?: return DeclarationFinder.DeclarationLocation.NONE

        val targetPsi = KaNavigationUtils.resolveToSourcePsi(refExpr, project, fo)
            ?: return DeclarationFinder.DeclarationLocation.NONE

        val targetFo = findFileObjectForReferencedElement(targetPsi, refExpr, fo)
            ?: return DeclarationFinder.DeclarationLocation.NONE

        val targetOffset = LineEndUtil.convertCrToDocumentOffset(
            targetPsi.containingFile.text, targetPsi.textOffset
        )

        val handle = (targetPsi as? KtNamedDeclaration)
            ?.let { KotlinElementHandle.fromPsi(it, targetFo) }

        return if (handle != null) {
            DeclarationFinder.DeclarationLocation(targetFo, targetOffset, handle)
        } else {
            DeclarationFinder.DeclarationLocation(targetFo, targetOffset)
        }
    }.getOrElse { e ->
        KotlinLogger.INSTANCE.logException("KotlinDeclarationFinder.findDeclaration failed", e)
        DeclarationFinder.DeclarationLocation.NONE
    }
}
