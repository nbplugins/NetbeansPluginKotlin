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

import io.github.nbplugins.kotlin.nbm.document.TextRangeDiff
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
        moveFileOnDisk: ((FileObject, FileObject, String, String) -> FileObject)? = null,
        renameFolderOnDisk: ((FileObject, FileObject, String) -> FileObject)? = null,
        deleteFileOnDisk: ((FileObject) -> Unit)? = null,
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
            moveFileOnDisk = moveFileOnDisk ?: { file, parent, name, extension ->
                file.lock().use { lock -> file.move(lock, parent, name, extension) }
            },
            renameFolderOnDisk = renameFolderOnDisk ?: { folder, _, name ->
                folder.lock().use { lock ->
                    folder.rename(lock, name, "")
                    folder.parent.getFileObject(name)
                        ?: error("Folder rename did not produce ${folder.parent.path}/$name")
                }
            },
            deleteFileOnDisk = deleteFileOnDisk ?: { file -> file.delete() },
        )
        return Fixture(root, documents, transaction)
    }

    /** Creates an existing file and its live document with [text]. */
    private fun Fixture.existing(name: String, text: String): FileObject = existingIn(root, name, text)

    /** Creates an existing file and document beneath [parent], mirroring disk and buffer content. */
    private fun Fixture.existingIn(parent: FileObject, name: String, text: String): FileObject {
        val file = parent.createData(name)
        file.getOutputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
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

    /** Verifies hunk staging uses and restores an unsaved leading editor-buffer baseline. */
    fun testHunkCommitAndUndo_preservesUnsavedLeadingBaseline() {
        val fixture = fixture()
        val baseline = "// unsaved leading edit\npackage source\n\nfun source() = 1\n"
        val source = fixture.existing("Source.kt", baseline)
        val changed = "// unsaved leading edit\npackage target\n\nfun source() = 1\n"

        fixture.transaction.captureExisting(source)
        fixture.transaction.stageText(source, changed, ::applyHunks)
        fixture.transaction.commit()

        assertEquals("the package hunk must be applied after the unsaved leading edit", changed, fixture.text(source))
        fixture.transaction.undo()
        assertEquals("undo must restore the exact live baseline", baseline, fixture.text(source))
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

    /** Verifies a same-folder Kotlin file rename participates in commit and undo. */
    fun testCommitAndUndo_restoresRenamedFilePath() {
        val fixture = fixture()
        val source = fixture.existing("Before.kt", "fun renamed() = 1\n")

        fixture.transaction.renameFile(source, "After", "kt")
        fixture.transaction.commit()

        assertNull("commit must remove the original file name", fixture.root.getFileObject("Before.kt"))
        assertNotNull("commit must create the requested target name", fixture.root.getFileObject("After.kt"))
        fixture.transaction.undo()

        assertNotNull("undo must restore the original file name", fixture.root.getFileObject("Before.kt"))
        assertNull("undo must remove the target file name", fixture.root.getFileObject("After.kt"))
    }

    /** Verifies an exact file rename is a no-op through commit and undo. */
    fun testRenameFile_sameNameIsNoOp() {
        val fixture = fixture()
        val source = fixture.existing("Before.kt", "fun before() = 1\n")

        fixture.transaction.renameFile(source, "Before", "kt")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertNotNull("original file must remain", fixture.root.getFileObject("Before.kt"))
        assertEquals("fun before() = 1\n", fixture.text(source))
    }

    /** Verifies a case-only file rename uses the requested spelling and undo restores it. */
    fun testCommitAndUndo_renamesFileByCaseOnly() {
        val fixture = fixture()
        val source = fixture.existing("Before.kt", "fun before() = 1\n")

        fixture.transaction.renameFile(source, "before", "kt")
        fixture.transaction.commit()

        assertNull("commit must remove original spelling", fixture.root.getFileObject("Before.kt"))
        assertNotNull("commit must use requested spelling", fixture.root.getFileObject("before.kt"))
        fixture.transaction.undo()

        assertNotNull("undo must restore original spelling", fixture.root.getFileObject("Before.kt"))
        assertNull("undo must remove case-only spelling", fixture.root.getFileObject("before.kt"))
    }

    /** Verifies a failed final case-only file rename restores the original spelling and removes its temporary path. */
    fun testCommitFailure_restoresFileAfterSecondCaseOnlyRenameLegFails() {
        var moveCalls = 0
        val fixture = fixture(
            moveFileOnDisk = { file, parent, name, extension ->
                moveCalls += 1
                if (moveCalls == 2) error("Injected final case-only file rename failure")
                file.lock().use { lock -> file.move(lock, parent, name, extension) }
            },
        )
        val source = fixture.existing("Before.kt", "fun before() = 1\n")

        fixture.transaction.renameFile(source, "before", "kt")
        try {
            fixture.transaction.commit()
            fail("commit must fail when the final case-only file rename leg fails")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: the helper restores its temporary sibling before the transaction rolls back.
        }

        assertNotNull("failure must restore the original file spelling", fixture.root.getFileObject("Before.kt"))
        assertNull("failure must not create the requested spelling", fixture.root.getFileObject("before.kt"))
        assertFalse(
            "failure must remove the transaction-private temporary file",
            fixture.root.children.any { it.name.startsWith(".nbkotlin-") },
        )
        assertEquals("failure must preserve the original document", "fun before() = 1\n", fixture.textByPath(source))
    }

    /** Verifies file rename rejects an existing sibling before mutating the source. */
    fun testRenameFile_rejectsExistingTarget() {
        val fixture = fixture()
        val source = fixture.existing("Before.kt", "fun before() = 1\n")
        fixture.existing("Taken.kt", "fun taken() = 1\n")

        try {
            fixture.transaction.renameFile(source, "Taken", "kt")
            fail("rename must reject an occupied sibling name")
        } catch (_: IllegalStateException) {
            // Expected: collision validation happens before commit.
        }
        assertTrue("source must remain valid after collision rejection", source.isValid)
        assertNotNull("source must remain at its original path", fixture.root.getFileObject("Before.kt"))
    }

    /** Verifies an early folder rename is restored when the later transaction commit fails. */
    fun testRollback_restoresFolderRenamedBeforeSemanticWrites() {
        val fixture = fixture(failWriting = { it.nameExt == "Source.kt" })
        val folder = fixture.root.createFolder("before")
        val source = fixture.existingIn(folder, "Source.kt", "fun source() = 1\n")

        fixture.transaction.renameFolder(folder, "after")
        fixture.transaction.applyFolderRenamesNow()
        val renamed = fixture.root.getFileObject("after/Source.kt") ?: error("Expected renamed source")
        fixture.transaction.captureExisting(renamed)
        fixture.transaction.stageText(renamed, "fun source() = 2\n")

        try {
            fixture.transaction.commit()
            fail("commit must fail when the staged semantic write fails")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: rollback restores the physical source directory.
        }
        assertNotNull("rollback must restore the original folder", fixture.root.getFileObject("before/Source.kt"))
        assertNull("rollback must remove the early renamed folder", fixture.root.getFileObject("after"))
        assertTrue("the original FileObject remains valid after restoration", source.isValid)
        assertEquals("rollback must restore the original file path", "before/Source.kt", source.path)
    }

    /** Verifies an exact folder rename is a no-op through commit and undo. */
    fun testRenameFolder_sameNameIsNoOp() {
        val fixture = fixture()
        val folder = fixture.root.createFolder("before")
        fixture.existingIn(folder, "Source.kt", "fun source() = 1\n")

        fixture.transaction.renameFolder(folder, "before")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertNotNull("original folder must remain", fixture.root.getFileObject("before/Source.kt"))
    }

    /** Verifies a case-only folder rename preserves descendants and restores original spelling. */
    fun testCommitAndUndo_renamesFolderByCaseOnly() {
        val fixture = fixture()
        val folder = fixture.root.createFolder("Before")
        fixture.existingIn(folder, "Source.kt", "fun source() = 1\n")

        fixture.transaction.renameFolder(folder, "before")
        fixture.transaction.commit()

        assertNull("commit must remove original spelling", fixture.root.getFileObject("Before"))
        assertNotNull("commit must use requested spelling", fixture.root.getFileObject("before/Source.kt"))
        fixture.transaction.undo()

        assertNotNull("undo must restore original spelling", fixture.root.getFileObject("Before/Source.kt"))
        assertNull("undo must remove case-only spelling", fixture.root.getFileObject("before"))
    }

    /** Verifies a failed final case-only folder rename restores the original spelling and descendants. */
    fun testCommitFailure_restoresFolderAfterSecondCaseOnlyRenameLegFails() {
        var renameCalls = 0
        val fixture = fixture(
            renameFolderOnDisk = { folder, _, name ->
                renameCalls += 1
                if (renameCalls == 2) error("Injected final case-only folder rename failure")
                folder.lock().use { lock ->
                    folder.rename(lock, name, "")
                    folder.parent.getFileObject(name)
                        ?: error("Folder rename did not produce ${folder.parent.path}/$name")
                }
            },
        )
        val folder = fixture.root.createFolder("Before")
        fixture.existingIn(folder, "Source.kt", "fun source() = 1\n")

        fixture.transaction.renameFolder(folder, "before")
        try {
            fixture.transaction.commit()
            fail("commit must fail when the final case-only folder rename leg fails")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: the helper restores its temporary sibling before the transaction rolls back.
        }

        assertNotNull("failure must restore the original folder spelling", fixture.root.getFileObject("Before/Source.kt"))
        assertNull("failure must not create the requested spelling", fixture.root.getFileObject("before"))
        assertFalse(
            "failure must remove the transaction-private temporary folder",
            fixture.root.children.any { it.name.startsWith(".nbkotlin-") },
        )
    }

    /** Verifies a folder rename preserves descendants and restores their physical paths on undo. */
    fun testCommitAndUndo_restoresRenamedFolderAndDescendantText() {
        val fixture = fixture()
        val sourceFolder = fixture.root.createFolder("before")
        val nested = sourceFolder.createFolder("nested")
        val originalText = "package before.nested\n\nfun source() = 1\n"
        val source = fixture.existingIn(nested, "Source.kt", originalText)
        val readme = sourceFolder.createData("Readme.txt")

        fixture.transaction.renameFolder(sourceFolder, "after")
        fixture.transaction.captureExisting(source)
        fixture.transaction.commit()

        val renamedFolder = fixture.root.getFileObject("after")
        val renamedSource = renamedFolder?.getFileObject("nested/Source.kt")
        assertNotNull("commit must rename the selected folder", renamedFolder)
        assertNotNull("commit must retain Kotlin descendants", renamedSource)
        assertNotNull("commit must retain non-Kotlin descendants", renamedFolder?.getFileObject("Readme.txt"))
        assertEquals("physical directory rename must not rewrite the package directive", originalText, fixture.textByPath(renamedSource!!))

        fixture.transaction.undo()

        val restored = fixture.root.getFileObject("before/nested/Source.kt")
        assertNotNull("undo must restore the original folder path", restored)
        assertNull("undo must remove the renamed folder", fixture.root.getFileObject("after"))
        assertTrue("undo must preserve non-Kotlin descendants", readme.isValid)
        assertEquals("undo must retain the original package directive", originalText, fixture.textByPath(restored!!))
    }

    /** Verifies commit deletes a scheduled file and undo recreates it with its original text. */
    fun testCommitAndUndo_deletesFileAndRestoresItsText() {
        val fixture = fixture()
        val java = fixture.existing("Source.java", "class Source {}\n")

        fixture.transaction.deleteFile(java)
        fixture.transaction.commit()

        assertNull("commit must remove the scheduled file", fixture.root.getFileObject("Source.java"))

        fixture.transaction.undo()

        val restored = fixture.root.getFileObject("Source.java")
        assertNotNull("undo must recreate the deleted file", restored)
        assertEquals("class Source {}\n", fixture.text(restored!!))
    }

    /** Verifies a failed staged write prevents the scheduled deletion and keeps the original text. */
    fun testCommitFailure_doesNotDeleteScheduledFile() {
        val fixture = fixture(failWriting = { it.nameExt == "Failing.kt" })
        val java = fixture.existing("Source.java", "class Source {}\n")
        val failing = fixture.existing("Failing.kt", "fun failing() = 1\n")

        fixture.transaction.deleteFile(java)
        fixture.transaction.captureExisting(failing)
        fixture.transaction.stageText(failing, "fun failing() = 2\n")

        try {
            fixture.transaction.commit()
            fail("commit must fail when a staged write fails")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: the rollback path restores every participant.
        }

        assertNotNull("a failed commit must not delete the scheduled file", fixture.root.getFileObject("Source.java"))
        assertEquals("fun failing() = 1\n", fixture.text(failing))
    }

    /** Verifies a failed deletion restores earlier staged writes so no half-applied state remains. */
    fun testCommitFailure_rollsBackStagedWritesWhenDeletionFails() {
        val fixture = fixture(deleteFileOnDisk = { error("Injected deletion failure") })
        val java = fixture.existing("Source.java", "class Source {}\n")
        val kotlin = fixture.existing("Source.kt", "fun source() = 1\n")

        fixture.transaction.deleteFile(java)
        fixture.transaction.captureExisting(kotlin)
        fixture.transaction.stageText(kotlin, "fun source() = 2\n")

        try {
            fixture.transaction.commit()
            fail("commit must fail when the deletion fails")
        } catch (_: KotlinRefactoringTransaction.Failure) {
            // Expected: the staged write has been rolled back.
        }

        assertEquals("rollback must restore the staged document", "fun source() = 1\n", fixture.text(kotlin))
        assertTrue("the scheduled file must survive a failed deletion", java.isValid)
    }

    /** Verifies the snapshot comes from the open editor document, not the stale on-disk content. */
    fun testDeleteFile_capturesUnsavedEditorText() {
        val root = FileUtil.createMemoryFileSystem().root
        val java = root.createData("Source.java")
        java.getOutputStream().use { it.write("class Stale {}\n".toByteArray(Charsets.UTF_8)) }
        val document = DefaultStyledDocument().also { it.insertString(0, "class Edited {}\n", null) }
        val transaction = KotlinRefactoringTransaction(
            openDocument = { if (it == java) document else null },
            writeDocument = { _, editableDocument, text ->
                if (editableDocument.length > 0) editableDocument.remove(0, editableDocument.length)
                editableDocument.insertString(0, text, null)
            },
            saveDocument = { },
        )

        transaction.deleteFile(java)
        transaction.commit()
        assertNull("commit must delete the file", root.getFileObject("Source.java"))

        transaction.undo()

        val restored = root.getFileObject("Source.java") ?: error("Expected restored file")
        assertEquals("class Edited {}\n", restored.asText())
    }

    /** Verifies undo of a deleted file also reverses a folder rename so the path matches the original. */
    fun testCommitAndUndo_restoresDeletedFileInOriginalFolder() {
        val fixture = fixture()
        val folder = fixture.root.createFolder("before")
        val java = fixture.existingIn(folder, "Source.java", "class Source {}\n")

        fixture.transaction.renameFolder(folder, "after")
        fixture.transaction.deleteFile(java)
        fixture.transaction.commit()

        assertNull("commit must delete the file", fixture.root.getFileObject("after/Source.java"))
        assertNotNull("commit must still rename the folder", fixture.root.getFileObject("after"))

        fixture.transaction.undo()

        val restored = fixture.root.getFileObject("before/Source.java")
        assertNotNull("undo must recreate the file in its original folder", restored)
        assertEquals("class Source {}\n", fixture.text(restored!!))
        assertNull("undo must remove the renamed folder", fixture.root.getFileObject("after"))
    }

    /** Verifies a file cannot be deleted twice or both modified and deleted by one transaction. */
    fun testDeleteFile_rejectsDuplicateAndConflictingParticipants() {
        val fixture = fixture()
        val deleted = fixture.existing("Deleted.java", "class Deleted {}\n")
        val modified = fixture.existing("Modified.kt", "fun modified() = 1\n")

        fixture.transaction.deleteFile(deleted)
        try {
            fixture.transaction.deleteFile(deleted)
            fail("a file must not be scheduled for deletion twice")
        } catch (_: IllegalStateException) {
            // Expected: the duplicate is rejected before commit.
        }

        fixture.transaction.captureExisting(modified)
        try {
            fixture.transaction.deleteFile(modified)
            fail("a captured file must not also be scheduled for deletion")
        } catch (_: IllegalStateException) {
            // Expected: modifying and deleting one file in one transaction is contradictory.
        }
    }

    /** Verifies rollback after commit restores the deleted file, and a second rollback is a no-op. */
    fun testRollback_recreatesDeletedFileOnlyOnce() {
        val fixture = fixture()
        val java = fixture.existing("Source.java", "class Source {}\n")

        fixture.transaction.deleteFile(java)
        fixture.transaction.commit()
        fixture.transaction.rollback()
        fixture.transaction.rollback()

        val restored = fixture.root.getFileObject("Source.java")
        assertNotNull("rollback must recreate the file", restored)
        assertEquals("class Source {}\n", restored!!.asText())
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
