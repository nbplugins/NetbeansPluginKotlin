/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.hints

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import javax.swing.text.Document
import javax.swing.text.StyledDocument
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.KaUnusedImportsComputer
import io.github.nbplugins.kotlin.nbm.hints.fixes.KaImplementMembersFix
import io.github.nbplugins.kotlin.nbm.hints.fixes.KaQuickFix
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaChangeReturnTypeIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaConvertToBlockBodyIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaConvertToExpressionBodyIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaConvertTryFinallyToUseCallIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaRemoveEmptyClassBodyIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaRemoveEmptyPrimaryConstructorIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaRemoveExplicitTypeIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaReplaceSizeCheckWithIsNotEmptyIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaSpecifyTypeIntention
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.elementsInRange
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinError
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.netbeans.modules.csl.api.*
import org.openide.text.NbDocument
import org.netbeans.modules.csl.api.HintsProvider.HintsManager
import org.netbeans.modules.csl.api.HintSeverity
import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.openide.cookies.SaveCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.netbeans.api.project.FileOwnerQuery

class KotlinHintsProvider : HintsProvider {

    companion object {

        // ── K2 primary path ──────────────────────────────────────────────────

        /** K2 intentions: all semantically-aware intentions that need [KaApplicableIntention]. */
        private fun listOfK2Intentions(
            parserResult: KotlinParserResult,
            kaKtFile: KtFile,
            psi: PsiElement
        ): List<KaApplicableIntention> {
            val doc = parserResult.snapshot.source.getDocument(false)
            return listOf(
                KaRemoveExplicitTypeIntention(doc, kaKtFile, psi),
                KaSpecifyTypeIntention(doc, kaKtFile, psi),
                KaConvertToBlockBodyIntention(doc, kaKtFile, psi),
                KaConvertToExpressionBodyIntention(doc, kaKtFile, psi),
                KaChangeReturnTypeIntention(doc, kaKtFile, psi),
                KaConvertTryFinallyToUseCallIntention(doc, kaKtFile, psi),
                KaReplaceSizeCheckWithIsNotEmptyIntention(doc, kaKtFile, psi),
                KaRemoveEmptyClassBodyIntention(doc, kaKtFile, psi),
                KaRemoveEmptyPrimaryConstructorIntention(doc, kaKtFile, psi)
            ).filter { it.isApplicable(psi.textRange.startOffset) }
        }

        private fun KaDiagnosticError.listOfK2QuickFixes(kaKtFile: KtFile): List<KaQuickFix> = listOf(
            KaImplementMembersFix(this, kaKtFile)
        ).filter(KaQuickFix::isApplicable)

        private fun RuleContext.k2QuickFixList(kaKtFile: KtFile): List<KaQuickFix> =
            parserResult.diagnostics
                .filterIsInstance(KaDiagnosticError::class.java)
                .flatMap { it.listOfK2QuickFixes(kaKtFile) }

        /**
         * Wraps a K2 [HintFix] so that after [HintFix.implement] runs, the file is saved and
         * the [KotlinAnalysisAPISession] cache is invalidated for the owning project.
         *
         * Without this, the session's [KtFile] retains pre-edit text, so hints reappear
         * on the next [computeSuggestions] pass even though the document was already edited.
         */
        private fun wrapWithInvalidate(fix: HintFix, fileObject: FileObject?): HintFix =
            object : HintFix {
                override fun getDescription() = fix.description
                override fun isSafe() = fix.isSafe()
                override fun isInteractive() = fix.isInteractive()
                override fun implement() {
                    fix.implement()
                    if (fileObject == null) return
                    // Force save so the new K2 session reads the updated disk content.
                    runCatching {
                        DataObject.find(fileObject)
                            ?.getLookup()?.lookup(SaveCookie::class.java)
                            ?.save()
                    }
                    // Invalidate the cached session so next parse creates a fresh K2 tree.
                    val project = FileOwnerQuery.getOwner(fileObject.toURI())
                    if (project != null) {
                        KotlinAnalysisAPISession.invalidate(project)
                    }
                }
            }

    }

    override fun computeSuggestions(hintsManager: HintsManager, ruleContext: RuleContext,
                                    hints: MutableList<Hint>, offset: Int) {
        val parserResult = ruleContext.parserResult as KotlinParserResult
        val doc = ruleContext.doc as StyledDocument

        val lineNumber = NbDocument.findLineNumber(doc, offset)
        val lastLine = NbDocument.findLineNumber(doc, doc.length)

        val lineStartOffset = NbDocument.findLineOffset(doc, lineNumber)
        val lineEndOffset = if (lineNumber < lastLine) NbDocument.findLineOffset(doc, lineNumber + 1) else doc.length

        val kaKtFile = parserResult.kaKtFile

        val intentions = parserResult.ktFile.elementsInRange(TextRange(lineStartOffset, lineEndOffset))
                .toMutableList()
                .apply {
                    val elem = parserResult.ktFile.findElementAt(offset)
                    if (elem != null) add(elem)
                }
                .map { psi ->
                    // K2 primary path: use K2 intentions when a K2-session KtFile is available.
                    // PSI elements must come from kaKtFile (K2 session tree), not the K1 parser
                    // tree, because analyze(kaKtFile) { element.returnType } resolves modules by
                    // the element's VirtualFile and will throw if the element is from a different tree.
                    val fixes: List<HintFix> = if (kaKtFile != null) {
                        val kaPsi = kaKtFile.findElementAt(psi.textRange.startOffset) ?: psi
                        val fileObject = parserResult.snapshot.source.fileObject
                        val k2fixes = listOfK2Intentions(parserResult, kaKtFile, kaPsi)
                            .filter { it.isApplicable(kaPsi.textRange.startOffset) }
                            .map { fix -> wrapWithInvalidate(fix, fileObject) }
                        KotlinLogger.INSTANCE.logInfo("K2 suggestions at offset=${psi.textRange.startOffset}: kaPsi=${kaPsi.javaClass.simpleName}, fixes=${k2fixes.size} [${k2fixes.joinToString { it.description }}]")
                        k2fixes
                    } else emptyList()
                    fixes.map { fix ->
                        Hint(
                            KotlinRule(HintSeverity.CURRENT_LINE_WARNING),
                            fix.description,
                            parserResult.snapshot.source.fileObject,
                            OffsetRange(offset, offset),
                            listOf(fix),
                            20
                        )
                    }
                }

        hints.addAll(
                intentions.flatMap { it }
                        .distinctBy { it.description }
        )
    }

    override fun computeHints(hintsManager: HintsManager, ruleContext: RuleContext, hints: MutableList<Hint>) {
        val parserResult = ruleContext.parserResult as KotlinParserResult
        val ktFile = parserResult.ktFile
        val kaKtFile = parserResult.kaKtFile

        if (kaKtFile != null) {
            // K2 primary path
            val hintsComputer = KotlinHintsComputer(parserResult)
            ktFile.accept(hintsComputer)
            val fileObject = parserResult.snapshot?.source?.fileObject
            with(hints) {
                addAll(ruleContext.k2QuickFixList(kaKtFile).map { fix ->
                    fix.createHintWith(wrapWithInvalidate(fix, fileObject))
                })
                addAll(hintsComputer.hints)
                addAll(KaUnusedImportsComputer(parserResult, kaKtFile).getUnusedImports())
            }
        }
    }

    override fun computeSelectionHints(hintsManager: HintsManager, ruleContext: RuleContext,
                                       list: List<Hint>, i: Int, i2: Int) {
    }

    override fun cancel() {}

    override fun getBuiltinRules() = emptyList<Rule>()

    override fun createRuleContext() = KotlinRuleContext()

    override fun computeErrors(hintsManager: HintsManager, ruleContext: RuleContext,
                               list: List<Hint>, errors: MutableList<Error>) {
        errors.addAll(ruleContext.parserResult.diagnostics)
    }

}

fun Document.atomicChange(change: Document.() -> Unit) = NbDocument.runAtomicAsUser(this as StyledDocument, { change() })