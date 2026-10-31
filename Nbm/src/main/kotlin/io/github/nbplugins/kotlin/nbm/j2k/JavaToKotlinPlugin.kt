/*
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
 */
package io.github.nbplugins.kotlin.nbm.j2k

import io.github.nbplugins.kotlin.nbm.refactoring.KotlinRefactoringTransaction
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.log.KotlinLogger
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.DialogDisplayer
import org.openide.NotifyDescriptor
import org.openide.cookies.OpenCookie
import org.openide.cookies.SaveCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups

/**
 * Converts one Java file to Kotlin inside the NetBeans refactoring lifecycle.
 *
 * Running as a refactoring gives the conversion what an ad-hoc file write cannot: the `.kt`
 * creation and the `.java` removal are applied and rolled back together, and the whole change is
 * registered as one **Undo Last Refactoring** entry.
 *
 * The plugin performs no work itself; all mutation happens in [JavaToKotlinElement], which the
 * session invokes after the user presses **Refactor** in the Refactoring window opened by
 * [JavaToKotlinUI].
 *
 * The plugin deliberately does **not** implement
 * [org.netbeans.modules.refactoring.spi.ProgressProvider]: NetBeans registers the parameters panel
 * of a refactoring as a progress listener before deciding whether to show that panel, and then
 * treats a refactoring with a progress-providing plugin as one that needs a parameters dialog. The
 * conversion has no parameters, so it would get an empty "Convert to Kotlin" dialog with a
 * **Finish** button instead of the Refactoring window.
 *
 * @param refactoring carrier holding the Java file to convert and the failure message channel
 */
class JavaToKotlinPlugin(
    private val refactoring: JavaToKotlinRefactoring,
) : RefactoringPlugin {

    /**
     * Validates the conversion target before NetBeans prepares the refactoring.
     *
     * The check must happen here, not only in [prepare]: with no parameters, NetBeans runs
     * `prepare` on a dialog it is about to throw away, so a problem returned from `prepare` is
     * silently lost and the Refactoring window just closes. A `preCheck` problem is displayed to
     * the user.
     *
     * @return a fatal problem when the conversion cannot start, otherwise `null`
     */
    override fun preCheck(): Problem? = refactoring.validate()

    /** @return no fast problem; the conversion has no user-editable parameters. */
    override fun fastCheckParameters(): Problem? = null

    /** @return no parameter problem. */
    override fun checkParameters(): Problem? = null

    /** Cancels no external work because the conversion runs entirely in the refactoring session. */
    override fun cancelRequest() = Unit

    /**
     * Re-validates the conversion target and registers the single apply element.
     *
     * The target check is repeated here rather than only in [preCheck] so that a Kotlin file
     * created while the Refactoring window was open is never overwritten.
     *
     * @param bag NetBeans bag that receives the mutation element
     * @return a fatal problem when the conversion cannot start, otherwise `null`
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        refactoring.validate()?.let { return it }
        bag.add(refactoring, JavaToKotlinElement(refactoring))
        return null
    }
}

/**
 * Performs the Java to Kotlin conversion as one undoable transaction.
 *
 * The Kotlin file is created by [KotlinRefactoringTransaction.createFile] before the analysis
 * session is rebuilt, because the session discovers its sources from disk. Writing the converted
 * text and deleting the Java original happen in the same [KotlinRefactoringTransaction.commit],
 * and [undoChange] restores both through the transaction retained after a successful commit. Undo
 * recreates the Java file as a new `FileObject`, so every entry point resolves the target through
 * [JavaToKotlinRefactoring.currentJavaFile] and a redo converts the restored file.
 *
 * `performChange` never throws: the refactoring session registers its undo entry even when an
 * element fails, so a thrown exception would leave a broken entry in the Undo Last Refactoring
 * history. Unexpected failures are instead recorded in [JavaToKotlinRefactoring.failureMessage]
 * after the transaction has been rolled back, and shown to the user from here: the Refactoring
 * window closes as soon as the session ends and has no way to report an element's failure.
 * Ordinary conversion failures are already reported by [Java2KotlinConverter.convertInSession]
 * with a more specific message.
 *
 * @param refactoring carrier holding the Java file to convert and the failure message channel
 */
class JavaToKotlinElement(
    private val refactoring: JavaToKotlinRefactoring,
) : SimpleRefactoringElementImplementation() {

    /** Successful transaction retained so [undoChange] can restore both files. */
    private var transaction: KotlinRefactoringTransaction? = null

    /** @return user-visible operation name shown in the refactoring undo history. */
    override fun getText(): String = "Convert to Kotlin"

    /** @return user-visible operation name shown in the refactoring preview. */
    override fun getDisplayText(): String = getText()

    /** @return the converted Java file, so the undo entry points at the original location. */
    override fun getLookup(): Lookup = Lookups.fixed(currentJavaFile())

    /**
     * @return the Java file, which is also the parent for the removal half of the change and must
     * therefore be writable for the element to stay enabled
     */
    override fun getParentFile(): FileObject = currentJavaFile()

    /** @return no position, because the change spans two files and the preview is not used. */
    override fun getPosition(): PositionBounds? = null

    /**
     * Creates the Kotlin file, fills it with the converted source, and deletes the Java original.
     *
     * A failure at any step rolls the transaction back, so a failed conversion never leaves a
     * Kotlin file behind and never loses the Java source.
     */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        try {
            val project = refactoring.project
            // Undo recreates the deleted Java file, so a redo must convert the new handle.
            val javaFile = refactoring.currentJavaFile()
                ?: error("The Java source file no longer exists.")
            saveJavaFile(javaFile)

            val current = KotlinRefactoringTransaction()
            pending = current
            val kotlinFile = current.createFile(
                javaFile.parent,
                "${javaFile.name}.kt",
                "",
            )
            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getSession(project)
            val converted = Java2KotlinConverter.convertInSession(session, project, javaFile, kotlinFile)
            if (converted == null) {
                refactoring.failureMessage = "Java to Kotlin conversion failed for ${javaFile.nameExt}."
                current.rollback()
                pending = null
                return
            }
            current.stageText(kotlinFile, converted)
            current.deleteFile(javaFile)
            current.commit()
            transaction = current
            pending = null
            openKotlinFile(kotlinFile)
            deleteStaleClassFile(javaFile)
            KotlinLogger.INSTANCE.logInfo("JavaToKotlinElement.performChange: converted ${javaFile.path}")
        } catch (error: Throwable) {
            KotlinLogger.INSTANCE.logException("JavaToKotlinElement.performChange failed", error)
            val message = error.message ?: error.javaClass.simpleName
            refactoring.failureMessage = message
            showFailureDialog(message)
        } finally {
            runCatching { pending?.rollback() }
                .onFailure { error ->
                    KotlinLogger.INSTANCE.logException("JavaToKotlinElement rollback failed", error)
                }
            runCatching { KotlinAnalysisAPISession.invalidate(refactoring.project) }
        }
    }

    /** Restores the Java original and removes the converted Kotlin file through the transaction. */
    override fun undoChange() {
        runCatching {
            val current = transaction
            if (current == null) {
                KotlinLogger.INSTANCE.logWarning("JavaToKotlinElement.undoChange: no retained transaction")
            } else {
                current.undo()
                transaction = null
            }
            KotlinAnalysisAPISession.invalidate(refactoring.project)
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("JavaToKotlinElement.undoChange failed", error)
        }
    }

    /**
     * Shows an unexpected conversion failure without blocking the calling refactoring thread.
     *
     * `performChange` runs on a background thread, hence `notifyLater`.
     *
     * @param message the failure reason recorded in [JavaToKotlinRefactoring.failureMessage]
     */
    private fun showFailureDialog(message: String) {
        runCatching {
            DialogDisplayer.getDefault().notifyLater(
                NotifyDescriptor.Message(
                    "Java to Kotlin conversion failed: $message",
                    NotifyDescriptor.ERROR_MESSAGE,
                ),
            )
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("JavaToKotlinElement: could not show the failure", error)
        }
    }

    /**
     * Resolves the Java file to convert, including one recreated by undo before a redo.
     *
     * The refactoring framework requires a non-null parent file and lookup, so the stale handle is
     * returned when the target is gone; [performChange] reports that case instead of converting.
     *
     * @return the current Java file
     */
    private fun currentJavaFile(): FileObject =
        refactoring.currentJavaFile() ?: refactoring.javaFile

    /**
     * Saves [javaFile] so the analysis session reads the text the user currently sees.
     *
     * The session discovers its sources from disk, so an unsaved editor buffer would be
     * converted in its older saved state and then deleted together with the Java original.
     * A save failure therefore propagates to [performChange]'s failure handler, which aborts the
     * conversion, instead of being swallowed.
     *
     * @param javaFile the Java source that is about to be converted and removed
     * @throws java.io.IOException when the editor buffer could not be written
     */
    private fun saveJavaFile(javaFile: FileObject) {
        // A file without a DataObject cannot have an editor buffer attached, so there is nothing
        // to save; only a failed save of an existing buffer must abort the conversion.
        val dataObject = runCatching { DataObject.find(javaFile) }.getOrNull() ?: return
        dataObject.lookup.lookup(SaveCookie::class.java)?.save()
    }

    /** Opens [kotlinFile] in the editor so the converted source is visible after conversion. */
    private fun openKotlinFile(kotlinFile: FileObject) {
        runCatching {
            DataObject.find(kotlinFile).lookup.lookup(OpenCookie::class.java)?.open()
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("JavaToKotlinElement: could not open ${kotlinFile.path}", error)
        }
    }

    /**
     * Removes a stale `.class` file next to the converted source.
     *
     * It is build output rather than a transaction participant, so it is not restored by undo.
     */
    private fun deleteStaleClassFile(javaFile: FileObject) {
        val classFile = javaFile.parent?.getFileObject(javaFile.name, "class") ?: return
        runCatching { classFile.delete() }
            .onFailure { error ->
                KotlinLogger.INSTANCE.logException("JavaToKotlinElement: could not delete ${classFile.path}", error)
            }
    }
}
