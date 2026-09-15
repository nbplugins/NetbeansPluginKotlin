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

import org.jetbrains.kotlin.log.KotlinLogger
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.text.NbDocument
import javax.swing.text.StyledDocument

/**
 * Applies a staged multi-file refactoring change atomically from the user's perspective.
 *
 * Existing files are snapshotted before staging. Files created through [createFile] are owned by the
 * transaction and are therefore deleted by [rollback] and [undo]. Callers are responsible for K2
 * session invalidation after a completed change, rollback, or undo.
 *
 * @param openDocument resolves a writable editor document for a file
 * @param writeDocument replaces a document's text; injectable to test rollback deterministically
 * @param saveDocument persists an editor document and releases its NetBeans file lock before a move
 */
internal class KotlinRefactoringTransaction internal constructor(
    private val openDocument: (FileObject) -> StyledDocument? = ::openEditorDocument,
    private val writeDocument: (FileObject, StyledDocument, String) -> Unit = ::replaceDocumentText,
    private val saveDocument: (FileObject) -> Unit = ::saveEditorDocument,
) {

    /** Identifies a file whose transaction operation could not be completed. */
    class Failure(val file: FileObject, cause: Throwable) : IllegalStateException(
        "Could not apply refactoring transaction for ${file.path}",
        cause,
    )

    private data class Entry(
        val file: FileObject,
        val existedBefore: Boolean,
        val originalText: String?,
        val document: StyledDocument,
        var stagedText: String? = null,
        var stagedWriter: ((StyledDocument, String, String) -> Unit)? = null,
    )

    private data class MoveEntry(
        val originalFile: FileObject,
        val originalParent: FileObject,
        val originalName: String,
        val originalExtension: String,
        val targetParent: FileObject,
        var movedFile: FileObject? = null,
    )

    private val entries = linkedMapOf<FileObject, Entry>()
    private val moves = mutableListOf<MoveEntry>()
    private val ownedFolders = mutableListOf<FileObject>()
    private var committed = false
    private var movesApplied = false
    private var restoring = false

    /**
     * Records a physical file move that will be applied atomically with staged document changes.
     *
     * @param file existing Kotlin source file to move
     * @param targetParent destination folder, which must not already contain the same file name
     */
    fun moveFile(file: FileObject, targetParent: FileObject) {
        check(file.isValid) { "Cannot move invalid file ${file.path}" }
        check(!file.isFolder) { "Move transaction supports files; enumerate Kotlin directory descendants first." }
        val originalParent = file.parent ?: error("Cannot move root file ${file.path}")
        check(targetParent.isValid && targetParent.isFolder) { "Invalid target folder ${targetParent.path}" }
        check(targetParent.getFileObject(file.name, file.ext) == null) {
            "Target file already exists: ${targetParent.path}/${file.nameExt}"
        }
        captureExisting(file)
        moves += MoveEntry(file, originalParent, file.name, file.ext, targetParent)
    }

    /**
     * Creates a destination folder owned by this transaction when it does not already exist.
     *
     * @param parent existing folder in which to create or find the child
     * @param name simple child folder name
     * @return the existing or newly created folder; only a newly created folder is deleted on undo
     */
    fun createFolder(parent: FileObject, name: String): FileObject {
        parent.getFileObject(name)?.let { existing ->
            check(existing.isFolder) { "Target path is not a folder: ${existing.path}" }
            return existing
        }
        val folder = parent.createFolder(name)
        ownedFolders += folder
        return folder
    }

    /**
     * Captures [file]'s original text and opens its NetBeans editor document exactly once.
     *
     * @param file an existing source file that this transaction may modify
     * @param originalText optional snapshot taken before an external semantic engine mutated PSI
     * @return the opened document associated with [file]
     */
    fun captureExisting(file: FileObject, originalText: String? = null): StyledDocument {
        entries[file]?.let { return it.document }
        check(file.isValid) { "Cannot capture invalid file ${file.path}" }
        val document = requireDocument(file)
        val snapshot = originalText ?: document.getText(0, document.length)
        entries[file] = Entry(
            file,
            existedBefore = true,
            originalText = snapshot,
            document = document,
        )
        KotlinLogger.INSTANCE.logInfo(
            "KotlinRefactoringTransaction.captureExisting: path=${file.path}, " +
                "snapshot=${describeText(snapshot)}, document=${describeText(document.getText(0, document.length))}"
        )
        return document
    }

    /**
     * Creates and seeds a file owned by this transaction so an analysis session can discover it.
     *
     * @param parent parent directory in which to create the file
     * @param name target file name including extension
     * @param initialText text required before the analysis engine runs
     * @return the newly-created file
     */
    fun createFile(parent: FileObject, name: String, initialText: String): FileObject {
        check(parent.getFileObject(name) == null) { "Target file already exists: ${parent.path}/$name" }
        val file = parent.createData(name)
        try {
            // The standalone K2 session discovers a new target from disk, before the document is
            // modified by the engine. Persist the seed first; subsequent staged changes stay as
            // NetBeans document edits so refactoring undo can restore them.
            file.getOutputStream().use { output -> output.write(initialText.toByteArray(Charsets.UTF_8)) }
            val document = requireDocument(file)
            if (document.getText(0, document.length) != initialText) {
                writeDocument(file, document, initialText)
            }
            entries[file] = Entry(file, existedBefore = false, originalText = null, document = document)
            return file
        } catch (error: Throwable) {
            runCatching { if (file.isValid) file.delete() }
            throw Failure(file, error)
        }
    }

    /**
     * Stages the final text for a previously captured or transaction-created [file].
     *
     * @param file transaction participant to change
     * @param text final document text; a later call replaces an earlier staged value
     */
    fun stageText(file: FileObject, text: String) {
        val entry = entries[file] ?: error("File was not captured by this transaction: ${file.path}")
        entry.stagedText = text
        entry.stagedWriter = null
    }

    /**
     * Stages [text] for [file] using [writer] instead of a whole-document replacement.
     *
     * The writer receives the immutable pre-transaction text and final text while [document] is
     * still unchanged. It may apply minimal edits and scoped formatting, but a later rollback or
     * undo always restores the exact original snapshot.
     *
     * @param file transaction participant to change
     * @param text final document text
     * @param writer atomic edit strategy for this participant
     */
    fun stageText(
        file: FileObject,
        text: String,
        writer: (document: StyledDocument, originalText: String, finalText: String) -> Unit,
    ) {
        val entry = entries[file] ?: error("File was not captured by this transaction: ${file.path}")
        entry.stagedText = text
        entry.stagedWriter = writer
    }

    /**
     * Applies all staged text changes, rolling back every participant if a write fails.
     *
     * @throws Failure if a document could not be written; all successfully changed existing files
     *                 have been restored and transaction-created files have been deleted
     */
    fun commit() {
        check(!committed) { "Refactoring transaction was already committed." }
        val stagedEntries = entries.values.filter { it.stagedText != null }
        try {
            stagedEntries.forEach { entry ->
                check(entry.file.isValid) { "Target file is invalid: ${entry.file.path}" }
                check(openDocument(entry.file) === entry.document) {
                    "Target document changed while preparing transaction: ${entry.file.path}"
                }
            }
            // A NetBeans editor document remains attached to its current FileObject. Write all
            // staged content while every source path is still valid; moving first invalidates the
            // source FileObject and makes CloneableEditorSupport reject a later document edit.
            stagedEntries.forEach { entry ->
                try {
                    KotlinLogger.INSTANCE.logInfo(
                        "KotlinRefactoringTransaction.commit: writing path=${entry.file.path}, " +
                            "snapshot=${describeText(entry.originalText ?: "")}, " +
                            "before=${describeDocument(entry.document)}, target=${describeText(entry.stagedText!!)}"
                    )
                    entry.stagedWriter?.invoke(entry.document, entry.originalText ?: "", entry.stagedText!!)
                        ?: writeDocument(entry.file, entry.document, entry.stagedText!!)
                    KotlinLogger.INSTANCE.logInfo(
                        "KotlinRefactoringTransaction.commit: wrote path=${entry.file.path}, " +
                            "after=${describeDocument(entry.document)}"
                    )
                } catch (error: Throwable) {
                    throw Failure(entry.file, error)
                }
            }
            // NetBeans retains a FileLock for every modified editor document until it is saved.
            // Persist every staged document before FileObject.move() attempts to acquire its lock.
            stagedEntries.forEach { entry -> saveDocument(entry.file) }
            applyMoves()
            committed = true
        } catch (error: Throwable) {
            rollback()
            if (error is Failure) throw error
            val failed = stagedEntries.lastOrNull()?.file ?: moves.lastOrNull()?.originalFile ?: entries.values.lastOrNull()?.file
            if (failed != null) throw Failure(failed, error)
            throw error
        }
    }

    /** Restores original documents, reverses owned physical moves, and removes owned files/folders. */
    fun rollback() = restoreOriginalState()

    /** Reverts a successfully committed refactoring using its original snapshots and paths. */
    fun undo() = restoreOriginalState()

    /** Applies all registered physical moves after every document has been validated. */
    private fun applyMoves() {
        moves.forEach { move ->
            val file = move.movedFile ?: move.originalFile
            check(file.isValid) { "Move source is invalid: ${move.originalFile.path}" }
            check(move.targetParent.getFileObject(move.originalName, move.originalExtension) == null) {
                "Move target already exists: ${move.targetParent.path}/${move.originalFile.nameExt}"
            }
            try {
                file.lock().use { lock ->
                    move.movedFile = file.move(lock, move.targetParent, move.originalName, move.originalExtension)
                }
                // FileObject identity changes across a NetBeans move, but the editor document is
                // intentionally retained. Re-key the snapshot so staged writes and later undo use
                // the physical object now located at the destination.
                val entry = entries.remove(file)
                if (entry != null) entries[move.movedFile!!] = entry.copy(file = move.movedFile!!)
                movesApplied = true
            } catch (error: Throwable) {
                throw Failure(file, error)
            }
        }
    }

    /** Restores documents before paths, retaining the first cleanup failure. */
    private fun restoreOriginalState() {
        if (restoring) return
        restoring = true
        var failure: Throwable? = null
        entries.values.filter { it.existedBefore }.forEach { entry ->
            runCatching {
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRefactoringTransaction.restore: writing path=${entry.file.path}, " +
                        "snapshot=${describeText(entry.originalText ?: "")}, before=${describeDocument(entry.document)}"
                )
                writeDocument(entry.file, entry.document, entry.originalText!!)
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRefactoringTransaction.restore: wrote path=${entry.file.path}, " +
                        "after=${describeDocument(entry.document)}"
                )
                // Persist every restored document before any path reversal. This both makes rollback
                // durable if a later move fails and releases the DataEditorSupport file lock.
                saveDocument(entry.file)
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRefactoringTransaction.restore: saved path=${entry.file.path}"
                )
            }.onFailure { error ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinRefactoringTransaction.restore failed for ${entry.file.path}", error
                )
                if (failure == null) failure = error
            }
        }
        reverseMoves().onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinRefactoringTransaction.reverseMoves failed", error)
            if (failure == null) failure = error
        }
        // FileObject.move() may replace the on-disk content from its own filesystem state after the
        // pre-move editor save. Reapply each original snapshot through the FileObject at its final
        // restored path so Undo persists the package directive, not merely the retained document.
        entries.values.filter { it.existedBefore }.forEach { entry ->
            runCatching {
                val finalDocument = requireDocument(entry.file)
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRefactoringTransaction.restoreAfterMoves: writing path=${entry.file.path}, " +
                        "snapshot=${describeText(entry.originalText ?: "")}, before=${describeDocument(finalDocument)}"
                )
                writeDocument(entry.file, finalDocument, entry.originalText!!)
                saveDocument(entry.file)
                entries[entry.file] = entry.copy(document = finalDocument)
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRefactoringTransaction.restoreAfterMoves: saved path=${entry.file.path}, " +
                        "after=${describeDocument(finalDocument)}"
                )
            }.onFailure { error ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinRefactoringTransaction.restoreAfterMoves failed for ${entry.file.path}", error
                )
                if (failure == null) failure = error
            }
        }
        entries.values.filterNot { it.existedBefore }.forEach { entry ->
            runCatching { if (entry.file.isValid) entry.file.delete() }
                .onFailure { if (failure == null) failure = it }
        }
        ownedFolders.asReversed().forEach { folder ->
            runCatching { if (folder.isValid && folder.children.isEmpty()) folder.delete() }
                .onFailure { if (failure == null) failure = it }
        }
        committed = false
        restoring = false
        failure?.let { throw Failure(entries.values.firstOrNull()?.file ?: moves.first().originalFile, it) }
    }

    /** Moves each successfully moved file back to its original parent and name. */
    private fun reverseMoves(): Result<Unit> = runCatching {
        if (!movesApplied) return@runCatching
        moves.asReversed().forEach { move ->
            val moved = move.movedFile ?: return@forEach
            if (!moved.isValid) return@forEach
            check(move.originalParent.getFileObject(move.originalName, move.originalExtension) == null) {
                "Cannot restore moved file because original path is occupied: ${move.originalParent.path}/${move.originalName}.${move.originalExtension}"
            }
            KotlinLogger.INSTANCE.logInfo(
                "KotlinRefactoringTransaction.reverseMoves: moving ${moved.path} to ${move.originalParent.path}, " +
                    "document=${entries[moved]?.let { describeDocument(it.document) } ?: "missing"}"
            )
            moved.lock().use { lock ->
                move.movedFile = moved.move(lock, move.originalParent, move.originalName, move.originalExtension)
            }
            val entry = entries.remove(moved)
            if (entry != null) entries[move.movedFile!!] = entry.copy(file = move.movedFile!!)
            KotlinLogger.INSTANCE.logInfo(
                "KotlinRefactoringTransaction.reverseMoves: moved to ${move.movedFile!!.path}, " +
                    "document=${entries[move.movedFile!!]?.let { describeDocument(it.document) } ?: "missing"}"
            )
        }
        movesApplied = false
    }

    /** Produces bounded, one-line diagnostic text without logging full source contents. */
    private fun describeText(text: String): String =
        "length=${text.length}, head=${text.take(160).replace("\n", "\\n")}"

    /** Produces a bounded description of the live document, including the package-header evidence. */
    private fun describeDocument(document: StyledDocument): String =
        describeText(document.getText(0, document.length))

    /** Obtains a document or fails with a clear transaction error. */
    private fun requireDocument(file: FileObject): StyledDocument =
        openDocument(file) ?: error("Could not open editor document for ${file.path}")

    private companion object {
        /** Opens the NetBeans editor document so it participates in refactoring undo bookkeeping. */
        fun openEditorDocument(file: FileObject): StyledDocument? =
            DataObject.find(file).lookup.lookup(EditorCookie::class.java)?.openDocument()

        /** Replaces [document] as one user-visible atomic edit. */
        fun replaceDocumentText(file: FileObject, document: StyledDocument, text: String) {
            NbDocument.runAtomicAsUser(document) {
                if (document.length > 0) document.remove(0, document.length)
                document.insertString(0, text, null)
            }
        }

        /** Persists a modified editor document so its DataEditorSupport releases the source file lock. */
        fun saveEditorDocument(file: FileObject) {
            DataObject.find(file).lookup.lookup(EditorCookie::class.java)?.saveDocument()
        }
    }
}
