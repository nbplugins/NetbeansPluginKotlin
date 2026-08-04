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
        fixture.transaction.stageText(target, "package target\n\nfun moved() = 2\n")
        fixture.transaction.commit()
        fixture.transaction.undo()

        assertTrue("pre-existing target must not be deleted", target.isValid())
        assertEquals("package target\n\nfun existing() = 1\n", fixture.text(target))
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

    /** Test-only transaction collaborators. */
    private data class Fixture(
        val root: FileObject,
        val documents: MutableMap<FileObject, StyledDocument>,
        val transaction: KotlinRefactoringTransaction,
    )
}
