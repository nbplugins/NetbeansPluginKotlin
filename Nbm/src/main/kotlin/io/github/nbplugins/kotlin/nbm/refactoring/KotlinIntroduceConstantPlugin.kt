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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.navigation.moveCaretToOffset
import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.ConstantDestination
import io.github.nbplugins.kotlin.refactoring.KaIntroduceConstantComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Introduce Constant** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaIntroduceConstantComputer]:
 *  1. `prepare()` validates the constant expression and populates [bag] with occurrence elements
 *     and a single [KotlinIntroduceConstantApplyElement] that performs the transformation.
 *  2. If the cursor is not on a compile-time constant expression a fatal [Problem] is returned.
 *
 * @param refactoring the carrier [KotlinIntroduceConstantRefactoring]
 */
class KotlinIntroduceConstantPlugin(
    private val refactoring: KotlinIntroduceConstantRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the expression at the cursor and populates [bag] with:
     *  - one read-only [KotlinFindUsagesResultElement] per occurrence (for the preview pane),
     *  - one [KotlinIntroduceConstantApplyElement] that performs the actual text transformation.
     *
     * @return a fatal [Problem] if the expression is not a compile-time constant, `null` otherwise
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroduceConstantComputer(
            ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
        )
        return when (val outcome = computer.compute()) {
            is KaIntroduceConstantComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinIntroduceConstantPlugin.prepare: NotApplicable at offsets " +
                            "${refactoring.startOffset}..${refactoring.endOffset} in ${fo.path}"
                )
                null
            }
            is KaIntroduceConstantComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException(
                    "KotlinIntroduceConstantPlugin.prepare: Error", outcome.error
                )
                Problem(true, outcome.error.message ?: "Introduce Constant failed")
            }
            is KaIntroduceConstantComputer.Outcome.Ready -> {
                val result = outcome.result

                for (range in result.occurrenceRanges) {
                    runCatching {
                        bag.add(
                            refactoring,
                            KotlinFindUsagesResultElement(OffsetRange(range.startOffset, range.endOffset), fo),
                        )
                    }
                }

                bag.add(
                    refactoring,
                    KotlinIntroduceConstantApplyElement(fo, nbProject, refactoring),
                )
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the introduce-constant transformation.
 *
 * Strategy:
 *  1. Re-runs [KaIntroduceConstantComputer] to get a fresh result against the current K2 session.
 *  2. Replaces occurrences (back-to-front) with [KotlinIntroduceConstantRefactoring.chosenName].
 *  3. Inserts `const val NAME: Type = expr` at the chosen destination:
 *     - [ConstantDestination.TOP_LEVEL]: inserted as a top-level declaration before the enclosing class.
 *     - [ConstantDestination.COMPANION_OBJECT]: inserted inside the companion object body (creating
 *       one if needed).
 *  4. Invalidates the K2 session.
 *
 * The change is applied as **minimal, targeted document edits** (never a whole-document replace), so a
 * native editor Undo (Ctrl+Z) reverses the small local edits and leaves the caret at the edit site —
 * no manual caret restoration is needed. [undoChange] is a snapshot-based fallback kept only for
 * non-editor undo paths (the editor Ctrl+Z never routes through it).
 *
 * @param declarationFile  the file containing the expression
 * @param nbProject        the NetBeans project (for K2 session access and invalidation)
 * @param refactoring      the carrier holding options chosen by the user
 */
class KotlinIntroduceConstantApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroduceConstantRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var snapshot: String? = null

    override fun getText(): String = "Introduce constant"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(declarationFile)
    override fun getParentFile(): FileObject = declarationFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(declarationFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    override fun performChange() {
        val activeBefore: TopComponent? = runCatching { TopComponent.getRegistry().activated }.getOrNull()
        try {
            runCatching {
                val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
                val nbProject2 = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return@runCatching

                val session = KotlinAnalysisAPISession.getSession(nbProject2)
                val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching

                val computer = KaIntroduceConstantComputer(
                    ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
                )
                val ready = computer.compute() as? KaIntroduceConstantComputer.Outcome.Ready
                    ?: return@runCatching
                val result = ready.result

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                val chosenName = refactoring.chosenName.ifBlank {
                    result.suggestedNames.firstOrNull() ?: "MY_CONST"
                }
                val typeAnnotation = result.typeText?.let { ": $it" } ?: ""

                // When "Replace all occurrences" is off, replace only the primary expression.
                // Sorted back-to-front so each edit leaves the offsets of the not-yet-edited
                // (earlier) occurrences valid while we mutate the live document.
                val rangesToReplace = if (refactoring.replaceAll) {
                    result.occurrenceRanges.sortedByDescending { it.startOffset }
                } else {
                    listOf(result.expressionRange).sortedByDescending { it.startOffset }
                }

                // Post-replacement view of the text, used only to compute insertion offsets and
                // indentation; the live document itself is edited minimally further below.
                var replacedText = originalText
                for (range in rangesToReplace) {
                    replacedText = replacedText.substring(0, range.startOffset) +
                            chosenName +
                            replacedText.substring(range.endOffset)
                }

                // Build the constant declaration text.
                val constDeclaration = "const val $chosenName$typeAnnotation = ${result.expressionText}"

                // Net offset shift from replacements that precede a given original offset — maps an
                // original-text offset into post-replacement (replacedText / live-document) space.
                fun adjustedOffset(rawOffset: Int): Int {
                    var shift = 0
                    for (range in rangesToReplace) {
                        if (range.endOffset <= rawOffset) {
                            shift += chosenName.length - (range.endOffset - range.startOffset)
                        }
                    }
                    return rawOffset + shift
                }

                // Adjusted expression start after replacements — where chosenName appears in the body.
                val exprStart = result.expressionRange.startOffset
                val lowerShift = rangesToReplace
                    .filter { it.endOffset <= exprStart }
                    .sumOf { chosenName.length - (it.endOffset - it.startOffset) }
                val adjustedExprStart = exprStart + lowerShift

                // Declaration insertion in post-replacement coordinates: where and what to insert,
                // plus nameOffset (position of chosenName at the trigger site afterwards).
                var declInsertOffset: Int? = null
                var declInsertText: String? = null
                var nameOffset: Int? = null

                when (refactoring.destination) {
                    ConstantDestination.TOP_LEVEL -> {
                        val insertPos = adjustedOffset(result.topLevelInsertOffset)
                        val lineStart = replacedText.lastIndexOf('\n', insertPos - 1) + 1
                        val insertedText = "$constDeclaration\n\n"
                        declInsertOffset = lineStart
                        declInsertText = insertedText
                        // Insertion is before the expression (top-level is before any class body).
                        nameOffset = adjustedExprStart + insertedText.length
                    }

                    ConstantDestination.COMPANION_OBJECT -> {
                        val companionBodyOff = result.companionBodyOffset
                        val companionCreateOff = result.companionCreateOffset
                        if (companionBodyOff != null) {
                            // Insert as first member of existing companion object.
                            val insertPos = adjustedOffset(companionBodyOff)
                            val companionIndent = computeCompanionMemberIndent(replacedText, insertPos)
                            val insertedText = "\n$companionIndent$constDeclaration"
                            declInsertOffset = insertPos
                            declInsertText = insertedText
                            nameOffset = if (insertPos <= adjustedExprStart) {
                                adjustedExprStart + insertedText.length
                            } else adjustedExprStart
                        } else if (companionCreateOff != null) {
                            // Create companion object as first member of the enclosing class.
                            val insertPos = adjustedOffset(companionCreateOff)
                            val classIndent = computeClassMemberIndent(replacedText, insertPos)
                            val companion = "\n${classIndent}companion object {\n$classIndent    $constDeclaration\n$classIndent}"
                            declInsertOffset = insertPos
                            declInsertText = companion
                            nameOffset = if (insertPos <= adjustedExprStart) {
                                adjustedExprStart + companion.length
                            } else adjustedExprStart
                        }
                    }
                }

                // Apply the transformation as minimal, targeted edits (never a whole-document
                // replace). A native editor Undo then reverses these small local edits; the joined
                // caret-restore edit keeps the caret at the trigger site instead of at EOF.
                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val caretTargetOnUndo = minOf(refactoring.startOffset, originalText.length)
                val body: () -> Unit = {
                    // Join the caret-restore edit to this atomic compound (see joinCaretRestoreOnUndo).
                    joinCaretRestoreOnUndo(doc, fo, caretTargetOnUndo)
                    // Replace occurrences and insert the constant declaration as minimal, targeted
                    // edits (declInsertOffset is in post-replacement coordinates).
                    MinimalDocumentEdits.apply(doc, rangesToReplace, chosenName, declInsertOffset, declInsertText)
                    // Reformat the inserted region so a newly created companion object (and the
                    // declaration) get correct indentation from the real formatter.
                    val off = declInsertOffset
                    val text = declInsertText
                    if (off != null && text != null) {
                        val end = minOf(off + text.length, doc.length)
                        if (off < end) runCatching {
                            format(doc = doc, offset = off, startOffset = off, endOffset = end, proj = nbProject)
                        }
                    }
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
                }

                // Move caret to the constant name (mirrors IDEA's in-place rename start position).
                nameOffset?.let { off ->
                    SwingUtilities.invokeLater {
                        runCatching { moveCaretToOffset(doc, minOf(off, doc.length)) }
                    }
                }

                KotlinAnalysisAPISession.invalidate(nbProject)
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException("KotlinIntroduceConstantApplyElement.performChange failed", e)
            }
        } finally {
            activeBefore?.let { tc ->
                SwingUtilities.invokeLater { runCatching { tc.requestActive() } }
            }
        }
    }

    override fun undoChange() {
        // Fallback only: a native editor Ctrl+Z reverses the individual document edits made in
        // performChange() directly and never routes through this SPI hook. It is kept functional
        // for any non-editor undo path that does invoke it, restoring the pre-refactor snapshot.
        runCatching {
            val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
            val doc = openDocument(fo) ?: return@runCatching
            val original = snapshot ?: return@runCatching
            NbDocument.runAtomicAsUser(doc) {
                if (doc.length > 0) doc.remove(0, doc.length)
                doc.insertString(0, original, null)
            }
            KotlinAnalysisAPISession.invalidate(nbProject)
            val target = minOf(refactoring.startOffset, doc.length)
            SwingUtilities.invokeLater { restoreCaret(fo, target) }
        }
    }

    /**
     * Computes the indentation string for a new member inside a companion object.
     *
     * Looks at the whitespace on the line following the companion object `{`; if the body is
     * empty, falls back to doubling the class-level indent (4 spaces per level).
     */
    private fun computeCompanionMemberIndent(text: String, afterLBrace: Int): String {
        if (afterLBrace >= text.length) return "        "
        // Skip whitespace after { on the same line (but not the newline itself).
        var i = afterLBrace
        while (i < text.length && text[i] != '\n' && text[i] != '}') i++
        if (i < text.length && text[i] == '\n') {
            // Next line: read its leading whitespace.
            i++
            val start = i
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (i > start) return text.substring(start, i)
        }
        return "        " // fallback: 8 spaces (2 levels of 4)
    }

    /**
     * Computes the indentation for a member of the enclosing class.
     *
     * Reads the leading whitespace from the line after the class `{`; falls back to 4 spaces.
     */
    private fun computeClassMemberIndent(text: String, afterClassLBrace: Int): String {
        if (afterClassLBrace >= text.length) return "    "
        var i = afterClassLBrace
        while (i < text.length && text[i] != '\n' && text[i] != '}') i++
        if (i < text.length && text[i] == '\n') {
            i++
            val start = i
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (i > start) return text.substring(start, i)
        }
        return "    " // fallback: 4 spaces
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
