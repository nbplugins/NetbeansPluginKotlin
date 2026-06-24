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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.text.PositionRef
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.BadLocationException
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that handles [KotlinInlineVariableRefactoring] for Kotlin `val` declarations.
 *
 * When the user confirms the Inline Variable dialog, [prepare] is called:
 * 1. Resolves the `val` property at the cursor using [KaInlineVariableComputer].
 * 2. Adds one [KotlinInlineUsageReplaceElement] per read usage (individual checkbox in preview).
 * 3. Adds one [KotlinInlineDeclarationDeleteElement] for the declaration (individual checkbox).
 * 4. Returns a fatal [Problem] if there are write usages (only `val` properties are supported).
 *
 * Each element carries its own [PositionBounds] backed by [PositionRef] objects so that
 * offsets auto-adjust as other elements perform their changes, regardless of application order.
 * Unchecking an element in the preview panel skips its [performChange], giving the user
 * per-occurrence control.
 *
 * @param refactoring the [KotlinInlineVariableRefactoring] created by [KotlinInlineVariableAction]
 */
class KotlinInlineVariablePlugin(private val refactoring: KotlinInlineVariableRefactoring) :
    ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Computes the inline variable changes and populates [bag] with individual change elements.
     *
     * @return `null` on success, or a [Problem] describing why the variable cannot be inlined
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.refactoringSource.lookup(StyledDocument::class.java)
            ?: return null
        val fo = ProjectUtils.getFileObjectForDocument(doc)
            ?: return null
        val offset = refactoring.refactoringSource.lookup(Int::class.javaObjectType)
            ?: return null

        val project = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null
        val session = KotlinAnalysisAPISession.getSession(project)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null
        val allFiles = KotlinPsiManager.getFilesByProject(project)

        val result = KaInlineVariableComputer(ktFile, offset, session, allFiles).compute()
            ?: return Problem(true, "Cannot inline: no inlineable 'val' property found at cursor.")

        val writeCount = result.writeUsages.values.sumOf { it.size }
        if (writeCount > 0) {
            return Problem(
                true,
                "'${result.declarationName}' has $writeCount write usage(s) — " +
                    "inline is only supported for 'val' properties."
            )
        }

        // One element per read usage: individually checked/unchecked in the preview panel.
        for ((usageFile, ranges) in result.readUsages) {
            for (range in ranges) {
                bag.add(
                    refactoring,
                    KotlinInlineUsageReplaceElement(
                        usageFile, range.start, range.end,
                        result.initializerText, result.declarationName
                    )
                )
            }
        }

        // One element for the declaration deletion.
        val declarationFo = FileUtil.toFileObject(
            FileUtil.normalizeFile(java.io.File(result.declarationFilePath))
        ) ?: fo
        bag.add(
            refactoring,
            KotlinInlineDeclarationDeleteElement(
                declarationFo, result.declarationStart, result.declarationEnd,
                result.declarationName
            )
        )

        return null
    }
}

/**
 * Replaces one read occurrence of [declarationName] with [newText] in [parentFile].
 *
 * Uses [PositionBounds] backed by [PositionRef] so the stored offset auto-adjusts as
 * sibling elements modify the same document before or after this element.
 *
 * @param parentFile      file containing the usage
 * @param initialStart    start offset of the usage identifier (inclusive)
 * @param initialEnd      end offset of the usage identifier (exclusive)
 * @param newText         initializer text to substitute for the usage
 * @param declarationName variable name being inlined (shown in the preview panel label)
 */
class KotlinInlineUsageReplaceElement(
    private val parentFile: FileObject,
    private val initialStart: Int,
    private val initialEnd: Int,
    private val newText: String,
    private val declarationName: String,
) : SimpleRefactoringElementImplementation() {

    private var bounds: PositionBounds? = null
    private var savedOldText: String = ""

    override fun getText(): String = "Replace '$declarationName' with '$newText'"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(parentFile)
    override fun getParentFile(): FileObject = parentFile

    override fun getPosition(): PositionBounds? {
        if (bounds == null) {
            bounds = runCatching {
                val ces = DataObject.find(parentFile).getLookup()
                    .lookup(CloneableEditorSupport::class.java) ?: return null
                val start: PositionRef = ces.createPositionRef(initialStart, Bias.Forward)
                val end: PositionRef = ces.createPositionRef(initialEnd, Bias.Backward)
                PositionBounds(start, end)
            }.getOrNull()
        }
        return bounds
    }

    override fun performChange() {
        val b = getPosition() ?: return
        runCatching {
            val ec = DataObject.find(parentFile).getLookup()
                .lookup(EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            val start = b.begin.offset
            val end = b.end.offset
            val len = end - start
            if (len < 0) return
            savedOldText = doc.getText(start, len)
            NbDocument.runAtomicAsUser(doc) {
                try {
                    doc.remove(start, len)
                    doc.insertString(start, newText, null)
                } catch (_: BadLocationException) {}
            }
        }
    }

    override fun undoChange() {
        val b = bounds ?: return
        runCatching {
            val ec = DataObject.find(parentFile).getLookup()
                .lookup(EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            val start = b.begin.offset
            NbDocument.runAtomicAsUser(doc) {
                try {
                    doc.remove(start, newText.length)
                    doc.insertString(start, savedOldText, null)
                } catch (_: BadLocationException) {}
            }
        }
    }
}

/**
 * Deletes the `val` declaration of [declarationName] from [parentFile].
 *
 * The deletion range [[initialStart], [initialEnd]) is tracked via [PositionBounds] so the
 * offset auto-adjusts if usage-replacement elements modify the document first.
 *
 * @param parentFile      file containing the property declaration
 * @param initialStart    start offset of the declaration (inclusive)
 * @param initialEnd      end offset of the declaration (exclusive, includes trailing newline)
 * @param declarationName variable name being deleted (shown in the preview panel label)
 */
class KotlinInlineDeclarationDeleteElement(
    private val parentFile: FileObject,
    private val initialStart: Int,
    private val initialEnd: Int,
    private val declarationName: String,
) : SimpleRefactoringElementImplementation() {

    private var bounds: PositionBounds? = null
    private var savedText: String = ""

    override fun getText(): String = "Delete 'val $declarationName'"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(parentFile)
    override fun getParentFile(): FileObject = parentFile

    override fun getPosition(): PositionBounds? {
        if (bounds == null) {
            bounds = runCatching {
                val ces = DataObject.find(parentFile).getLookup()
                    .lookup(CloneableEditorSupport::class.java) ?: return null
                val start: PositionRef = ces.createPositionRef(initialStart, Bias.Forward)
                val end: PositionRef = ces.createPositionRef(initialEnd, Bias.Backward)
                PositionBounds(start, end)
            }.getOrNull()
        }
        return bounds
    }

    override fun performChange() {
        val b = getPosition() ?: return
        runCatching {
            val ec = DataObject.find(parentFile).getLookup()
                .lookup(EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            val start = b.begin.offset
            val end = b.end.offset
            val len = end - start
            if (len < 0) return
            savedText = doc.getText(start, len)
            doc.remove(start, len)
        }
    }

    override fun undoChange() {
        val b = bounds ?: return
        runCatching {
            val ec = DataObject.find(parentFile).getLookup()
                .lookup(EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            val start = b.begin.offset
            doc.insertString(start, savedText, null)
        }
    }
}
