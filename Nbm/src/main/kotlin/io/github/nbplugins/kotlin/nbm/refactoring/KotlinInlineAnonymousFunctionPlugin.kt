/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaInlineAnonymousFunctionComputer
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlineAnonymousFunctionProcessor
import org.jetbrains.kotlin.idea.k2.refactoring.util.LambdaToAnonymousFunctionUtil
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * Bridges NetBeans Inline Anonymous Function UI to IDEA's K2 inline processor.
 *
 * @param refactoring active document and caret selected by the user
 */
class KotlinInlineAnonymousFunctionPlugin(
    private val refactoring: KotlinInlineAnonymousFunctionRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Validates the immediate invocation and exposes it as the preview item plus one apply element.
     *
     * @param bag NetBeans refactoring element bag to populate
     * @return fatal validation problem, or `null` when a valid plan was prepared
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val sourceFile = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(sourceFile) ?: return null
        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(sourceFile.path) ?: return null
        return when (val outcome = KaInlineAnonymousFunctionComputer(ktFile, refactoring.offset).compute()) {
            is KaInlineAnonymousFunctionComputer.Outcome.NotApplicable -> null
            is KaInlineAnonymousFunctionComputer.Outcome.Error -> Problem(true, outcome.message)
            is KaInlineAnonymousFunctionComputer.Outcome.Ready -> {
                val range = outcome.result.call.textRange
                bag.add(
                    refactoring,
                    KotlinFindUsagesResultElement(OffsetRange(range.startOffset, range.endOffset), sourceFile),
                )
                bag.add(
                    refactoring,
                    KotlinInlineAnonymousFunctionApplyElement(sourceFile, nbProject, sourceFile.path, refactoring.offset),
                )
                null
            }
        }
    }
}

/**
 * Applies one IDEA-generated anonymous-function inline rewrite as a hunk-preserving transaction.
 *
 * @param sourceFile parent source file used by NetBeans preview and undo infrastructure
 * @param nbProject project owning the standalone K2 session
 * @param cursorFilePath session virtual path of [sourceFile]
 * @param cursorOffset invocation caret offset within [cursorFilePath]
 */
class KotlinInlineAnonymousFunctionApplyElement(
    private val sourceFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val cursorFilePath: String,
    private val cursorOffset: Int,
) : SimpleRefactoringElementImplementation() {

    private var transaction: KotlinRefactoringTransaction? = null

    override fun getText(): String = "Inline anonymous function"
    override fun getDisplayText(): String = text
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile

    override fun getPosition(): PositionBounds? = try {
        val support = DataObject.find(sourceFile).lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        PositionBounds(support.createPositionRef(0, Bias.Forward), support.createPositionRef(0, Bias.Backward))
    } catch (_: Exception) {
        null
    }

    /**
     * Rebuilds the K2 session after IDEA converts a lambda to an anonymous function, then delegates
     * the actual call substitution to IDEA's existing named-function inline strategy.
     *
     * The upstream anonymous-function processor performs both phases against one mutable IDEA
     * session. Standalone K2 cannot attach FIR to the newly inserted anonymous `fun` in that same
     * session, so this adapter persists neither intermediate form nor partial mutation: it derives
     * the intermediate text in PSI, refreshes the standalone session, and commits only final text.
     */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        try {
            val firstSession = KotlinAnalysisAPISession.getSession(nbProject)
            val cursorFile = firstSession.getKtFileForPath(cursorFilePath) ?: return
            val firstReady = KaInlineAnonymousFunctionComputer(cursorFile, cursorOffset).compute()
                as? KaInlineAnonymousFunctionComputer.Outcome.Ready ?: return
            val originalSourceText = cursorFile.text
            val affectedFile = fileObjectFor(firstReady.result.call.containingKtFile) ?: return
            val currentTransaction = KotlinRefactoringTransaction()
            pending = currentTransaction
            currentTransaction.captureExisting(affectedFile)
            val functionOffset = firstReady.result.function.textRange.startOffset
            val inlineFile: KtFile
            val namedFunction: KtNamedFunction
            if (firstReady.result.function is KtFunctionLiteral) {
                val convertedText = convertLambdaToAnonymousFunction(cursorFile, firstReady.result.function)
                KotlinAnalysisAPISession.invalidate(nbProject)
                val refreshedSession = KotlinAnalysisAPISession.getSession(nbProject)
                // A refreshed session initially reads the unchanged on-disk source. Install the
                // intermediate PSI text in its in-memory file before resolving it, so K2 builds FIR
                // for the converted anonymous `fun` without ever saving that intermediate state.
                refreshedSession.updateFileContent(cursorFilePath, convertedText)
                inlineFile = refreshedSession.getKtFileForPath(cursorFilePath)
                    ?: error("Inline Anonymous Function could not refresh the source file.")
                namedFunction = PsiTreeUtil.getParentOfType(
                    inlineFile.findElementAt(functionOffset),
                    KtNamedFunction::class.java,
                    false,
                )?.takeIf { it.nameIdentifier == null }
                    ?: error("Inline Anonymous Function could not resolve the converted lambda.")
            } else {
                inlineFile = cursorFile
                namedFunction = firstReady.result.function as? KtNamedFunction
                    ?: error("Inline Anonymous Function expected an anonymous function.")
            }
            val codeToInline = org.jetbrains.kotlin.idea.k2.refactoring.inline
                .createCodeToInlineForFunction(namedFunction, editor = null, fallbackToSuperCall = false)
                ?: error("Inline Anonymous Function could not prepare inline code.")
            val call = KotlinInlineAnonymousFunctionProcessor.findCallExpression(namedFunction)
                ?: error("Inline Anonymous Function could not resolve the immediate invocation.")
            remapAnonymousParameters(codeToInline, namedFunction, call)
            org.jetbrains.kotlin.idea.k2.refactoring.inline.codeInliner.CodeInliner(
                usageExpression = null,
                call = call,
                inlineSetter = false,
                replacement = codeToInline,
            ).doInline() ?: error("Inline Anonymous Function did not produce a replacement.")

            check(inlineFile.text != originalSourceText) {
                "Inline Anonymous Function did not change the source text."
            }
            currentTransaction.stageHunkText(affectedFile, inlineFile.text, nbProject)
            currentTransaction.commit()
            transaction = currentTransaction
            pending = null
            KotlinAnalysisAPISession.invalidate(nbProject)
        } catch (error: Throwable) {
            KotlinLogger.INSTANCE.logException("KotlinInlineAnonymousFunctionApplyElement.performChange failed", error)
            runCatching { pending?.rollback() }
                .onFailure {
                    KotlinLogger.INSTANCE.logException(
                        "KotlinInlineAnonymousFunctionApplyElement rollback failed",
                        it,
                    )
                }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }

    /**
     * Replaces IDEA's anonymous-function synthetic parameter keys (`p1`, `p2`, ...) with the real
     * names exposed by K2's invoke-call argument mapping.
     *
     * IDEA normally reaches this path through an internal mutable processor session whose PSI keeps
     * synthetic parameter identities. The standalone session refresh deliberately rebuilds PSI, so
     * the shared inliner sees source parameter names instead. Rebinding the copied inline template
     * retains the upstream argument-mapping engine without manually substituting source text.
     */
    private fun remapAnonymousParameters(
        codeToInline: org.jetbrains.kotlin.idea.refactoring.inline.codeInliner.CodeToInline,
        function: KtNamedFunction,
        call: org.jetbrains.kotlin.psi.KtExpression,
    ) {
        val arguments = (call as? org.jetbrains.kotlin.psi.KtCallExpression)
            ?.valueArguments
            ?.mapNotNull { it.getArgumentExpression() }
            ?: return
        function.valueParameters.forEachIndexed { index, parameter ->
            val argument = arguments.getOrNull(index) ?: return@forEachIndexed
            codeToInline.mainExpression
                ?.collectDescendantsOfType<KtSimpleNameExpression>()
                ?.filter { it.text == parameter.name }
                ?.forEach { reference -> reference.replace(argument.copy()) }
        }
    }

    /** Returns intermediate source where a lambda has IDEA's K2-derived anonymous-function signature. */
    private fun convertLambdaToAnonymousFunction(cursorFile: KtFile, function: org.jetbrains.kotlin.psi.KtFunction): String {
        val lambda = (function as? KtFunctionLiteral)?.parent as? org.jetbrains.kotlin.psi.KtLambdaExpression
            ?: return cursorFile.text
        val functionText = LambdaToAnonymousFunctionUtil.prepareFunctionText(lambda)
            ?: return cursorFile.text
        LambdaToAnonymousFunctionUtil.convertLambdaToFunction(lambda, functionText)
        return cursorFile.text
    }

    /** Restores the exact pre-refactoring source snapshot. */
    override fun undoChange() {
        runCatching { transaction?.undo() }
            .onFailure {
                KotlinLogger.INSTANCE.logException(
                    "KotlinInlineAnonymousFunctionApplyElement.undoChange failed",
                    it,
                )
            }
        KotlinAnalysisAPISession.invalidate(nbProject)
    }

    /** Converts a session virtual path to its writable NetBeans file. */
    private fun fileObjectFor(file: KtFile): FileObject? =
        file.virtualFile?.path?.let { FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(it))) }
}
