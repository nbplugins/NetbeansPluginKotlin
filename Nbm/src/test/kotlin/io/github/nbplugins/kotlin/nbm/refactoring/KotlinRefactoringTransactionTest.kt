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

import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.StyledDocument

/**
 * Unit tests for [KotlinRefactoringTransaction].
 *
 * The transaction is supplied with in-memory documents so these tests verify its commit, rollback,
 * and undo contracts independently of an editor window or K2 analysis session.
 */
class KotlinRefactoringTransactionTest : NbTestCase("KotlinRefactoringTransactionTest") {

    /** Creates a transaction test fixture backed by a NetBeans memory filesystem. */
    private fun fixture(
        failWriting: ((FileObject) -> Boolean)? = null,
    ): Fixture {
        val root = FileUtil.createMemoryFileSystem().root
        val documents = linkedMapOf<FileObject, StyledDocument>()
        val transaction = KotlinRefactoringTransaction(
            openDocument = { file -> documents.getOrPut(file) { DefaultStyledDocument() } },
            writeDocument = { file, document, text ->
                if (failWriting?.invoke(file) == true) error("Injected write failure for ${file.path}")
                if (document.length > 0) document.remove(0, document.length)
                document.insertString(0, text, null)
            },
        )
        return Fixture(root, documents, transaction)
    }

    /** Creates an existing file and its live document with [text]. */
    private fun Fixture.existing(name: String, text: String): FileObject {
        val file = root.createData(name)
        documents[file] = DefaultStyledDocument().also { it.insertString(0, text, null) }
        return file
    }

    /** Reads the in-memory document associated with [file]. */
    private fun Fixture.text(file: FileObject): String {
        val document = documents[file]
        assertNotNull("Expected document for ${file.path}", document)
        return document!!.getText(0, document.length)
    }

    /** Verifies commit changes every staged existing document and undo restores every snapshot. */
    fun testCommitAndUndo_restoresEveryExistingDocument() {
        val fixture = fixture()
        val source = fixture.existing("Source.kt", "fun source() = 1\n")
        val target = fixture.existing("Target.kt", "fun target() = 2\n")

        fixture.transaction.captureExisting(source)
        fixture.transaction.captureExisting(target)
        fixture.transaction.stageText(source, "fun source() = 10\n")
        fixture.transaction.stageText(target, "fun target() = 20\n")
        fixture.transaction.commit()

        assertEquals("fun source() = 10\n", fixture.text(source))
        assertEquals("fun target() = 20\n", fixture.text(target))

        fixture.transaction.undo()

        assertEquals("fun source() = 1\n", fixture.text(source))
        assertEquals("fun target() = 2\n", fixture.text(target))
    }

    /** Verifies an owned target file is retained at commit and deleted by undo. */
    fun testUndo_deletesTransactionCreatedFile() {
        val fixture = fixture()
        val target = fixture.transaction.createFile(fixture.root, "Target.kt", "package target\n\n")
        fixture.transaction.stageText(target, "package target\n\nfun moved() = 1\n")

        fixture.transaction.commit()
        assertTrue("created target must exist after commit", target.isValid())
        assertEquals("package target\n\nfun moved() = 1\n", fixture.text(target))

        fixture.transaction.undo()
        assertFalse("undo must delete only the transaction-created target", target.isValid())
    }

    /** Verifies undo restores an existing target rather than deleting it. */
    fun testUndo_restoresExistingTargetWithoutDeletingIt() {
        val fixture = fixture()
        val target = fixture.existing("Target.kt", "package target\n\nfun existing() = 1\n")

        fixture.transaction.captureExisting(target)
        fixture.transaction.stageText(target, "package target\n\nfun copied() = 2\n")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertTrue("pre-existing target must not be deleted", target.isValid())
        assertEquals("package target\n\nfun existing() = 1\n", fixture.text(target))
    }

    /** Verifies an Extract Super-style new target is removed while its source is restored by undo. */
    fun testExtractSuperLifecycle_undoRestoresSourceAndDeletesCreatedTarget() {
        val fixture = fixture()
        val source = fixture.existing("Greeter.kt", "package sample\n\nclass Greeter\n")
        val target = fixture.transaction.createFile(fixture.root, "IGreeter.kt", "package sample\n\n")

        fixture.transaction.captureExisting(source)
        fixture.transaction.stageText(source, "package sample\n\nclass Greeter : IGreeter\n")
        fixture.transaction.stageText(target, "package sample\n\ninterface IGreeter\n")
        fixture.transaction.commit()

        assertEquals("package sample\n\nclass Greeter : IGreeter\n", fixture.text(source))
        assertEquals("package sample\n\ninterface IGreeter\n", fixture.text(target))
        fixture.transaction.undo()

        assertEquals("package sample\n\nclass Greeter\n", fixture.text(source))
        assertFalse("undo must remove the generated Extract Super target", target.isValid())
    }

    /** Verifies an Extract Super-style existing target is restored rather than deleted by undo. */
    fun testExtractSuperLifecycle_undoRestoresExistingTarget() {
        val fixture = fixture()
        val source = fixture.existing("Greeter.kt", "package sample\n\nclass Greeter\n")
        val target = fixture.existing("IGreeter.kt", "package sample\n\ninterface Existing\n")

        fixture.transaction.captureExisting(source)
        fixture.transaction.captureExisting(target)
        fixture.transaction.stageText(source, "package sample\n\nclass Greeter : IGreeter\n")
        fixture.transaction.stageText(target, "package sample\n\ninterface IGreeter\n")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertEquals("package sample\n\nclass Greeter\n", fixture.text(source))
        assertTrue("undo must retain the pre-existing Extract Super target", target.isValid())
        assertEquals("package sample\n\ninterface Existing\n", fixture.text(target))
    }

    /** Verifies package rewriting uses physical line breaks rather than literal escape characters. */
    fun testRewritePackage_insertsLineBreaks() {
        val rewritten = KotlinCopyDeclarationApplyElement.rewritePackage(
            "package source\n\nfun greet() = Unit\n",
            "target",
        )

        assertEquals("package target\n\nfun greet() = Unit\n", rewritten)
        assertFalse("package header must not contain literal backslash escapes", rewritten.contains("\\n"))
    }

    /** Verifies a Copy Declaration-style target seed is replaced atomically by its final text. */
    fun testCommit_replacesCreatedTargetSeedWithCopiedDeclaration() {
        val fixture = fixture()
        val target = fixture.transaction.createFile(fixture.root, "Copied.kt", "package copied\n\n")

        fixture.transaction.stageText(target, "package copied\n\nfun copied() = 1\n")
        fixture.transaction.commit()

        assertEquals("package copied\n\nfun copied() = 1\n", fixture.text(target))
        fixture.transaction.undo()
        assertFalse("undo must remove the created copied target", target.isValid())
    }

    /** Verifies a hunk writer preserves unrelated text and undo restores exact originals. */
    fun testHunkCommitAndUndo_preservesUntouchedTextAndRestoresEveryDocument() {
        val fixture = fixture()
        val source = fixture.existing("Source.kt", "fun one() = 1\nodd  spacing\nfun two() = 2\n")
        val target = fixture.existing("Target.kt", "fun target() = 3\n")

        fixture.transaction.captureExisting(source)
        fixture.transaction.captureExisting(target)
        fixture.transaction.stageText(source, "fun one() = 10\nodd  spacing\nfun two() = 20\n", ::applyHunks)
        fixture.transaction.stageText(target, "fun target() = 30\n", ::applyHunks)
        fixture.transaction.commit()

        assertEquals("fun one() = 10\nodd  spacing\nfun two() = 20\n", fixture.text(source))
        assertEquals("fun target() = 30\n", fixture.text(target))
        fixture.transaction.undo()
        assertEquals("fun one() = 1\nodd  spacing\nfun two() = 2\n", fixture.text(source))
        assertEquals("fun target() = 3\n", fixture.text(target))
    }

    /** Verifies a hunk writer failure rolls back an earlier document without touching later ones. */
    fun testHunkCommitFailure_rollsBackEarlierDocumentAndSkipsLaterDocument() {
        val fixture = fixture()
        val source = fixture.existing("Source.kt", "fun source() = 1\n")
        val failing = fixture.existing("Failing.kt", "fun failing() = 2\n")
        val later = fixture.existing("Later.kt", "fun later() = 3\n")

        fixture.transaction.captureExisting(source)
        fixture.transaction.captureExisting(failing)
        fixture.transaction.captureExisting(later)
        fixture.transaction.stageText(source, "fun source() = 10\n", ::applyHunks)
        fixture.transaction.stageText(failing, "fun failing() = 20\n") { _, _, _ -> error("Injected hunk failure") }
        fixture.transaction.stageText(later, "fun later() = 30\n", ::applyHunks)

        try {
            fixture.transaction.commit()
            fail("commit must fail when a hunk writer throws")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: every participant remains at its original text.
        }
        assertEquals("fun source() = 1\n", fixture.text(source))
        assertEquals("fun failing() = 2\n", fixture.text(failing))
        assertEquals("fun later() = 3\n", fixture.text(later))
    }

    /** Verifies a failure after an earlier write rolls back documents and deletes an owned target. */
    fun testCommitFailure_rollsBackEarlierWritesAndDeletesCreatedFile() {
        var target: FileObject? = null
        var targetWrites = 0
        val fixture = fixture(failWriting = {
            if (it == target) ++targetWrites >= 1 else false
        })
        val source = fixture.existing("Source.kt", "fun source() = 1\n")
        target = fixture.transaction.createFile(fixture.root, "Target.kt", "package target\n\n")
        val createdTarget = target ?: error("Expected transaction-created target")

        fixture.transaction.captureExisting(source)
        fixture.transaction.stageText(source, "fun source() = 2\n")
        fixture.transaction.stageText(createdTarget, "package target\n\nfun moved() = 1\n")

        try {
            fixture.transaction.commit()
            fail("commit must fail when the target write is injected to fail")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: the transaction has rolled back every earlier change.
        }
        assertEquals("the earlier source write must be restored", "fun source() = 1\n", fixture.text(source))
        assertFalse("rollback must remove transaction-created target", createdTarget.isValid())
    }

    /** Verifies the first snapshot is preserved while the last staged value wins. */
    fun testRepeatedCaptureAndStage_preservesFirstSnapshotAndLastStage() {
        val fixture = fixture()
        val source = fixture.existing("Source.kt", "fun source() = 1\n")

        fixture.transaction.captureExisting(source)
        fixture.transaction.stageText(source, "fun source() = 2\n")
        fixture.transaction.captureExisting(source)
        fixture.transaction.stageText(source, "fun source() = 3\n")
        fixture.transaction.commit()

        assertEquals("fun source() = 3\n", fixture.text(source))
        fixture.transaction.undo()
        assertEquals("fun source() = 1\n", fixture.text(source))
    }

    /** Applies the same descending minimal-hunk sequence used by multi-file refactorings. */
    private fun applyHunks(document: StyledDocument, originalText: String, finalText: String) {
        TextRangeDiff.computeHunks(originalText, finalText)
            .sortedByDescending { it.oldStart }
            .forEach { hunk ->
                if (hunk.oldEnd > hunk.oldStart) document.remove(hunk.oldStart, hunk.oldEnd - hunk.oldStart)
                val replacement = finalText.substring(hunk.newStart, hunk.newEnd)
                if (replacement.isNotEmpty()) document.insertString(hunk.oldStart, replacement, null)
            }
    }

    /** Test-only transaction collaborators. */
    private data class Fixture(
        val root: FileObject,
        val documents: MutableMap<FileObject, StyledDocument>,
        val transaction: KotlinRefactoringTransaction,
    )
}
