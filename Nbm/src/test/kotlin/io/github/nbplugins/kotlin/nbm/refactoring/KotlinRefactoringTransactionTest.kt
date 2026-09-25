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

    /** Creates an existing file and document beneath [parent]. */
    private fun Fixture.existingIn(parent: FileObject, name: String, text: String): FileObject {
        val file = parent.createData(name)
        documents[file] = DefaultStyledDocument().also { it.insertString(0, text, null) }
        return file
    }

    /** Reads the in-memory document associated with [file]. */
    private fun Fixture.text(file: FileObject): String {
        val document = documents[file]
        assertNotNull("Expected document for ${file.path}", document)
        return document!!.getText(0, document.length)
    }

    /** Finds the retained editor document after a FileObject changed identity through a move. */
    private fun Fixture.textByPath(file: FileObject): String {
        val document = documents.entries.firstOrNull { (knownFile, _) ->
            knownFile.path == file.path || !knownFile.isValid
        }?.value
        assertNotNull("Expected retained document for ${file.path}", document)
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

    /** Verifies an externally captured pre-mutation snapshot is restored by undo. */
    fun testCommitAndUndo_restoresProvidedPreMutationSnapshot() {
        val fixture = fixture()
        val source = fixture.existing("Source.kt", "package target\n\nfun source() = 1\n")
        val original = "package source\n\nfun source() = 1\n"

        fixture.transaction.captureExisting(source, original)
        fixture.transaction.stageText(source, "package target\n\nfun source() = 1\n")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertEquals("undo must restore the snapshot from before K2 PSI mutation", original, fixture.text(source))
    }

    /** Verifies undo removes an import that Change Package added to a same-package caller. */
    fun testCommitAndUndo_removesGeneratedSamePackageImport() {
        val fixture = fixture()
        val moved = fixture.existing("Moved.kt", "package new.target\n\nfun moved() = 1\n")
        val caller = fixture.existing("Caller.kt", "package old.source\n\nimport new.target.moved\n\nfun use() = moved()\n")
        val originalMoved = "package old.source\n\nfun moved() = 1\n"
        val originalCaller = "package old.source\n\nfun use() = moved()\n"

        fixture.transaction.captureExisting(moved, originalMoved)
        fixture.transaction.captureExisting(caller, originalCaller)
        fixture.transaction.stageText(moved, "package new.target\n\nfun moved() = 1\n")
        fixture.transaction.stageText(caller, "package old.source\n\nimport new.target.moved\n\nfun use() = moved()\n")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertEquals("package source must be restored", originalMoved, fixture.text(moved))
        assertEquals("undo must remove the generated import exactly", originalCaller, fixture.text(caller))
    }

    /** Verifies several directory descendants move and undo together in reverse physical-move order. */
    fun testCommitAndUndo_restoresEveryMovedDirectoryDescendant() {
        val fixture = fixture()
        val sourceFolder = fixture.root.createFolder("feature")
        val nestedSource = sourceFolder.createFolder("internal")
        val targetFolder = fixture.root.createFolder("target")
        val nestedTarget = targetFolder.createFolder("internal")
        val publicFile = fixture.existingIn(sourceFolder, "Public.kt", "package feature\n\nfun publicApi() = 1\n")
        val helperFile = fixture.existingIn(nestedSource, "Helper.kt", "package feature.internal\n\nfun helper() = 1\n")
        val retainedText = sourceFolder.createData("Readme.txt")

        fixture.transaction.moveFile(publicFile, targetFolder)
        fixture.transaction.moveFile(helperFile, nestedTarget)
        fixture.transaction.stageText(publicFile, "package target\n\nfun publicApi() = 1\n")
        fixture.transaction.stageText(helperFile, "package target.internal\n\nfun helper() = 1\n")
        fixture.transaction.commit()

        assertNotNull(targetFolder.getFileObject("Public.kt"))
        assertNotNull(nestedTarget.getFileObject("Helper.kt"))
        assertTrue("non-Kotlin source content must remain untouched", retainedText.isValid)
        fixture.transaction.undo()

        assertNotNull(sourceFolder.getFileObject("Public.kt"))
        assertNotNull(nestedSource.getFileObject("Helper.kt"))
        assertNull(targetFolder.getFileObject("Public.kt"))
        assertNull(nestedTarget.getFileObject("Helper.kt"))
        assertTrue("non-Kotlin source content must remain after undo", retainedText.isValid)
    }

    /** Verifies a physical move is committed and Undo Last Refactoring restores its path and text. */
    fun testCommitAndUndo_restoresMovedFilePathAndText() {
        val fixture = fixture()
        val sourceFolder = fixture.root.createFolder("source")
        val targetFolder = fixture.root.createFolder("target")
        val source = fixture.existingIn(sourceFolder, "Moved.kt", "package source\n\nfun moved() = 1\n")

        fixture.transaction.moveFile(source, targetFolder)
        fixture.transaction.stageText(source, "package target\n\nfun moved() = 1\n")
        fixture.transaction.commit()

        val moved = targetFolder.getFileObject("Moved.kt")
        assertNotNull("commit must physically move the file", moved)
        assertNull("source path must no longer exist", sourceFolder.getFileObject("Moved.kt"))
        assertEquals("package target\n\nfun moved() = 1\n", fixture.textByPath(moved!!))

        fixture.transaction.undo()

        val restored = sourceFolder.getFileObject("Moved.kt")
        assertNotNull("undo must restore the original source path", restored)
        assertNull("undo must remove the moved target path", targetFolder.getFileObject("Moved.kt"))
        assertEquals("package source\n\nfun moved() = 1\n", fixture.textByPath(restored!!))
    }

    /** Verifies undo writes the original snapshot through the FileObject returned by the reverse move. */
    fun testUndo_rewritesSnapshotAtRestoredFilePath() {
        val root = FileUtil.createMemoryFileSystem().root
        val sourceFolder = root.createFolder("source")
        val targetFolder = root.createFolder("target")
        val source = sourceFolder.createData("Moved.kt")
        val original = "package source\n\nfun moved() = 1\n"
        val target = "package target\n\nfun moved() = 1\n"
        val documents = mutableMapOf<FileObject, StyledDocument>()
        val persistedTexts = mutableMapOf(source.path to original)
        val transaction = KotlinRefactoringTransaction(
            openDocument = { file -> documents.getOrPut(file) { DefaultStyledDocument().also { it.insertString(0, persistedTexts[file.path] ?: "", null) } } },
            writeDocument = { file, document, text ->
                if (document.length > 0) document.remove(0, document.length)
                document.insertString(0, text, null)
                persistedTexts[file.path] = text
            },
            saveDocument = { },
        )

        transaction.moveFile(source, targetFolder)
        transaction.captureExisting(source, original)
        transaction.stageText(source, target)
        transaction.commit()
        val moved = targetFolder.getFileObject("Moved.kt") ?: error("Expected moved file")
        persistedTexts[moved.path] = target
        transaction.undo()

        val restored = sourceFolder.getFileObject("Moved.kt") ?: error("Expected restored file")
        assertEquals("undo must persist the snapshot at the restored physical path", original, persistedTexts[restored.path])
    }

    /** Verifies a later write failure prevents the physical move and restores source text. */
    fun testCommitFailure_doesNotMoveFileBeforeEveryDocumentIsWritten() {
        val fixture = fixture(failWriting = { it.nameExt == "Failing.kt" })
        val sourceFolder = fixture.root.createFolder("source")
        val targetFolder = fixture.root.createFolder("target")
        val source = fixture.existingIn(sourceFolder, "Moved.kt", "fun moved() = 1\n")
        val failing = fixture.existing("Failing.kt", "fun failing() = 1\n")

        fixture.transaction.moveFile(source, targetFolder)
        fixture.transaction.stageText(source, "fun moved() = 2\n")
        fixture.transaction.captureExisting(failing)
        fixture.transaction.stageText(failing, "fun failing() = 2\n")

        try {
            fixture.transaction.commit()
            fail("commit must fail when a staged write fails")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: no physical move occurs until every staged document is writable.
        }

        val restored = sourceFolder.getFileObject("Moved.kt")
        assertNotNull("failed document writes must leave the source path intact", restored)
        assertNull("failed document writes must not create the target path", targetFolder.getFileObject("Moved.kt"))
        assertEquals("fun moved() = 1\n", fixture.textByPath(restored!!))
        assertEquals("fun failing() = 1\n", fixture.text(failing))
    }

    /** Verifies every staged document is written and saved before the source file is moved. */
    fun testCommit_writesAndSavesEveryDocumentBeforePhysicalMove() {
        val root = FileUtil.createMemoryFileSystem().root
        val sourceFolder = root.createFolder("source")
        val targetFolder = root.createFolder("target")
        val source = sourceFolder.createData("Moved.kt")
        val usage = root.createData("Usage.kt")
        val sourceDocument = DefaultStyledDocument().also { it.insertString(0, "fun moved() = 1\n", null) }
        val usageDocument = DefaultStyledDocument().also { it.insertString(0, "fun usage() = moved()\n", null) }
        val documents = mapOf(source to sourceDocument, usage to usageDocument)
        val savedFiles = mutableListOf<FileObject>()
        val transaction = KotlinRefactoringTransaction(
            openDocument = { file -> documents[file] },
            writeDocument = { _, editableDocument, text ->
                if (editableDocument.length > 0) editableDocument.remove(0, editableDocument.length)
                editableDocument.insertString(0, text, null)
            },
            saveDocument = { file ->
                assertNotNull("a moved file must remain at its original path until saves complete", sourceFolder.getFileObject("Moved.kt"))
                savedFiles += file
            },
        )

        transaction.moveFile(source, targetFolder)
        transaction.stageText(source, "fun moved() = 2\n")
        transaction.captureExisting(usage)
        transaction.stageText(usage, "fun usage() = moved()\n")
        transaction.commit()

        assertEquals("all staged documents must be saved before the move", listOf(source, usage), savedFiles)
        assertNotNull("commit must still move the file after saving", targetFolder.getFileObject("Moved.kt"))
    }

    /** Verifies undo saves the moved source before restoring its original path. */
    fun testUndo_savesMovedSourceBeforePhysicalRestore() {
        val root = FileUtil.createMemoryFileSystem().root
        val sourceFolder = root.createFolder("source")
        val targetFolder = root.createFolder("target")
        val source = sourceFolder.createData("Moved.kt")
        val document = DefaultStyledDocument().also { it.insertString(0, "fun moved() = 1\n", null) }
        var savedMovedSourceBeforeRestore = false
        val transaction = KotlinRefactoringTransaction(
            openDocument = { document },
            writeDocument = { _, editableDocument, text ->
                if (editableDocument.length > 0) editableDocument.remove(0, editableDocument.length)
                editableDocument.insertString(0, text, null)
            },
            saveDocument = { file ->
                if (file.parent == targetFolder) {
                    savedMovedSourceBeforeRestore = sourceFolder.getFileObject("Moved.kt") == null
                }
            },
        )

        transaction.moveFile(source, targetFolder)
        transaction.stageText(source, "fun moved() = 2\n")
        transaction.commit()
        transaction.undo()

        assertTrue("undo must save the moved source before restoring its original path", savedMovedSourceBeforeRestore)
        assertNotNull("undo must restore the original path", sourceFolder.getFileObject("Moved.kt"))
    }

    /** Verifies empty destination folders created by the transaction are deleted on rollback. */
    fun testRollback_deletesTransactionOwnedEmptyFolder() {
        val fixture = fixture()
        val destination = fixture.transaction.createFolder(fixture.root, "generated")

        fixture.transaction.rollback()

        assertFalse("rollback must delete an unused transaction-owned folder", destination.isValid())
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
