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
 */
internal class KotlinRefactoringTransaction internal constructor(
    private val openDocument: (FileObject) -> StyledDocument? = ::openEditorDocument,
    private val writeDocument: (FileObject, StyledDocument, String) -> Unit = ::replaceDocumentText,
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
    )

    private val entries = linkedMapOf<FileObject, Entry>()

    /**
     * Captures [file]'s original text and opens its NetBeans editor document exactly once.
     *
     * @param file an existing source file that this transaction may modify
     * @return the opened document associated with [file]
     */
    fun captureExisting(file: FileObject): StyledDocument {
        entries[file]?.let { return it.document }
        check(file.isValid) { "Cannot capture invalid file ${file.path}" }
        val document = requireDocument(file)
        entries[file] = Entry(
            file,
            existedBefore = true,
            originalText = document.getText(0, document.length),
            document = document,
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
    }

    /**
     * Applies all staged text changes, rolling back every participant if a write fails.
     *
     * @throws Failure if a document could not be written; all successfully changed existing files
     *                 have been restored and transaction-created files have been deleted
     */
    fun commit() {
        val stagedEntries = entries.values.filter { it.stagedText != null }
        try {
            stagedEntries.forEach { entry ->
                check(entry.file.isValid) { "Target file is invalid: ${entry.file.path}" }
                check(openDocument(entry.file) === entry.document) {
                    "Target document changed while preparing transaction: ${entry.file.path}"
                }
            }
            stagedEntries.forEach { entry ->
                try {
                    writeDocument(entry.file, entry.document, entry.stagedText!!)
                } catch (error: Throwable) {
                    throw Failure(entry.file, error)
                }
            }
        } catch (error: Throwable) {
            rollback()
            if (error is Failure) throw error
            val failed = stagedEntries.lastOrNull()?.file ?: entries.values.lastOrNull()?.file
            if (failed != null) throw Failure(failed, error)
            throw error
        }
    }

    /** Restores original documents and removes only files this transaction created. */
    fun rollback() = restoreOriginalState()

    /** Reverts a successfully committed refactoring using its original snapshots. */
    fun undo() = restoreOriginalState()

    /** Restores existing documents before removing owned files, retaining the first failure. */
    private fun restoreOriginalState() {
        var failure: Throwable? = null
        entries.values.filter { it.existedBefore }.forEach { entry ->
            runCatching { writeDocument(entry.file, entry.document, entry.originalText!!) }
                .onFailure { if (failure == null) failure = it }
        }
        entries.values.filterNot { it.existedBefore }.forEach { entry ->
            runCatching { if (entry.file.isValid) entry.file.delete() }
                .onFailure { if (failure == null) failure = it }
        }
        failure?.let { throw Failure(entries.values.first().file, it) }
    }

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
    }
}
