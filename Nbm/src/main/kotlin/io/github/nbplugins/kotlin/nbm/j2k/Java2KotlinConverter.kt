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

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.SingletonModule
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import javax.swing.text.Document
import org.jetbrains.kotlin.j2k.ConverterSettings
import org.jetbrains.kotlin.j2k.PostProcessingTarget
import org.jetbrains.kotlin.j2k.k2.K2J2KPostProcessor
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.nj2k.NewJ2kWithProgressProcessor
import org.jetbrains.kotlin.nj2k.NewJavaToKotlinConverter
import org.netbeans.api.project.Project
import org.netbeans.modules.refactoring.api.RefactoringSession
import org.openide.DialogDisplayer
import org.openide.NotifyDescriptor
import org.openide.filesystems.FileObject
import org.openide.util.Exceptions
import org.openide.util.RequestProcessor

/**
 * Converts a Java source file into Kotlin using the IDEA K2 J2K engine
 * (module `netbeans-plugin-kotlin-j2k`).
 *
 * The engine is the real IntelliJ implementation compiled from
 * `submodules/IntellijCommunity`; this class is only the NetBeans adapter. It runs the
 * conversion as a headless NetBeans refactoring ([JavaToKotlinRefactoring] + [JavaToKotlinPlugin]),
 * which
 * 1. creates the sibling `.kt` file before the analysis session is rebuilt, so K2 sees it,
 * 2. runs the conversion and the K2 post-processing chain on the session PSI,
 * 3. writes the result into the `.kt` and deletes the `.java` as one transaction, which
 *    **Undo Last Refactoring** can revert in a single step.
 *
 * The whole flow runs on a background [RequestProcessor] thread: the post-processor calls
 * `ApplicationManager.getApplication().invokeAndWait(...)`, which must not be issued from the EDT.
 *
 * This is the **controller** layer: it wires the model (J2K engine, analysis session) to the view
 * (dialogs, editor). It contains no conversion logic of its own.
 */
object Java2KotlinConverter {

    /**
     * Schedules the conversion of [fo] (a Java file) to a sibling Kotlin file.
     *
     * The NetBeans action passes the file's [Document], its [Project], and the [FileObject];
     * the document is kept for the unsaved-changes check and otherwise unused.
     *
     * @param doc the document of [fo] as it was open in the editor, or `null`
     * @param proj the NetBeans project that owns [fo]
     * @param fo the Java source file to convert
     */
    @JvmStatic
    fun convert(doc: Document?, proj: Project, fo: FileObject) {
        RequestProcessor.getDefault().post { convertNow(proj, fo) }
    }

    /**
     * Runs the conversion synchronously on the calling (background) thread.
     *
     * The operation is executed as a headless NetBeans refactoring: a fresh [RefactoringSession] per
     * conversion (the session's `finished()` latch is one-way) applies the element prepared by
     * [JavaToKotlinPlugin], which in turn creates the `.kt`, writes the converted source and
     * deletes the `.java` as one transaction registered in **Undo Last Refactoring**. No
     * Refactoring panel is opened: the caller has already confirmed the operation.
     *
     * @param proj the NetBeans project that owns [javaFileObject]
     * @param javaFileObject the Java source file
     */
    internal fun convertNow(proj: Project, javaFileObject: FileObject) {
        val refactoring = JavaToKotlinRefactoring(proj, javaFileObject)
        try {
            val session = RefactoringSession.create("Convert to Kotlin")
            val problem = refactoring.preCheck() ?: refactoring.prepare(session)
            if (problem != null) {
                showError(problem.message ?: "Java to Kotlin conversion is not applicable.")
                return
            }
            // `false`: the transaction already saved every document it changed. A global
            // LifecycleManager.saveAll() would also write the user's unrelated unsaved buffers.
            session.doRefactoring(false)
            refactoring.failureMessage?.let { showError("Java to Kotlin conversion failed: $it") }
        } catch (t: Throwable) {
            Exceptions.printStackTrace(t)
            showError("Java to Kotlin conversion failed: ${t.message}")
        }
    }

    /**
     * Runs the IDEA J2K engine over [javaFileObject] and returns the resulting Kotlin text.
     *
     * The empty [kotlinFileObject] must already exist on disk and be part of [session]: the engine
     * needs a `KtFile` owned by the session, because the K2 post-processing analyses the
     * converted file. [session] is passed explicitly so tests can supply a session with the Kotlin
     * standard library on its classpath.
     *
     * @param session the analysis session that contains both files
     * @param proj the NetBeans project that provides the Kotlin code-style settings and the
     * PSI factory used by the formatting step
     * @param javaFileObject the Java source file being converted
     * @param kotlinFileObject the freshly created, empty Kotlin file
     * @return the converted Kotlin text, or `null` when a message has already been shown
     */
    internal fun convertInSession(
        session: KotlinAnalysisAPISession,
        proj: Project,
        javaFileObject: FileObject,
        kotlinFileObject: FileObject,
    ): String? {
        val ideaProject = session.session.project

        val javaVirtualFile = session.fileMap[javaFileObject.path]
        if (javaVirtualFile == null) {
            cleanup(kotlinFileObject)
            showError("${javaFileObject.path} is not part of the project's source roots.")
            return null
        }

        val javaPsi = runReadAction<PsiJavaFile?> {
            PsiManager.getInstance(ideaProject).findFile(javaVirtualFile) as? PsiJavaFile
        }
        if (javaPsi == null) {
            cleanup(kotlinFileObject)
            showError("Could not parse ${javaFileObject.name} as a Java file.")
            return null
        }

        val converter = NewJavaToKotlinConverter(
            ideaProject,
            SingletonModule.forProject(ideaProject),
            ConverterSettings.defaultSettings,
            targetFile = null,
        )
        val result = runReadAction {
            converter.elementsToKotlin(
                listOf(javaPsi),
                NewJ2kWithProgressProcessor.DEFAULT,
                bodyFilter = null,
                forInlining = false,
            )
        }
        val elementResult = result.results.firstOrNull()
        if (elementResult == null) {
            cleanup(kotlinFileObject)
            showError("The J2K engine produced no output for ${javaFileObject.name}.")
            return null
        }

        val kotlinText = withImports(elementResult.text, elementResult.importsToAdd)
        session.updateFileContent(kotlinFileObject.path, kotlinText)
        val ktFile = session.getKtFileForPath(kotlinFileObject.path)
        if (ktFile == null) {
            cleanup(kotlinFileObject)
            showError("Could not register $kotlinFileObject in the analysis session.")
            return null
        }

        // Must run off the EDT and without an outer read action: the post-processor issues its
        // own analysis read actions and applies changes through invokeAndWait.
        K2J2KPostProcessor().doAdditionalProcessing(
            PostProcessingTarget.MultipleFilesPostProcessingTarget(listOf(ktFile)),
            result.converterContext,
            null,
        )
        return formatConvertedText(ktFile.text, kotlinFileObject.nameExt, proj)
    }

    /**
     * Formats converted text with the project's Kotlin code-style preferences.
     *
     * The engine's own formatting step (`FormatCodeProcessing`) delegates to
     * `CodeStyleManager.reformat`, which is a no-op in the standalone session's mock application.
     * Without this pass the printer output keeps defects such as glued closing braces
     * (`}}` on one line). Text-based formatting through the plugin's own
     * [KotlinFormatterUtils] is the same path the refactoring actions use.
     *
     * A formatting failure is logged and the unformatted text is returned: writing slightly
     * unformatted Kotlin is better than aborting an otherwise successful conversion.
     *
     * @param text the converted Kotlin source
     * @param fileName file name used by the formatter PSI factory
     * @param proj project whose Kotlin formatting settings apply
     * @return the formatted text, or [text] when formatting failed
     */
    internal fun formatConvertedText(text: String, fileName: String, proj: Project): String =
        runCatching {
            KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.getSettings(proj))
            try {
                KotlinFormatterUtils.formatCode(text, fileName, proj, "\n")
            } finally {
                KotlinFormatterUtils.popSettings()
            }
        }.getOrElse {
            KotlinLogger.INSTANCE.logException("J2K: formatting the converted output failed", it)
            text
        }

    /**
     * Deletes a Kotlin file that a failed conversion had already created, so that no empty `.kt`
     * remains.
     *
     * Only used inside [convertInSession], which runs before any document in the transaction is
     * written; the refactoring element rolls the whole transaction back on a `null` result.
     *
     * @param kotlinFileObject the file to remove
     */
    private fun cleanup(kotlinFileObject: FileObject) {
        runCatching { kotlinFileObject.delete() }
    }

    /**
     * Prepends the imports the engine collected while converting to [text].
     *
     * The engine returns the package directive and the declarations in [text] and keeps imports
     * in [imports] separately (its IDE entry point adds them to the physical file afterwards).
     *
     * @param text the converted file text, possibly starting with a `package` directive
     * @param imports fully-qualified names to import
     * @return the file text with an import block inserted after the package directive
     */
    internal fun withImports(text: String, imports: Collection<FqName>): String {
        if (imports.isEmpty()) return text
        val importBlock = imports.asSequence()
            .map { "import ${it.asString()}" }
            .sorted()
            .joinToString("\n")
        val packageLine = PACKAGE_LINE.find(text)
        return if (packageLine != null) {
            val insertAt = packageLine.range.last + 1
            text.substring(0, insertAt) + "\n" + importBlock + "\n\n" +
                text.substring(insertAt).trimStart('\n')
        } else {
            importBlock + "\n\n" + text
        }
    }

    /** Matches the package directive line, including its line break. */
    private val PACKAGE_LINE = Regex("^package[ \\t]+[^\\r\\n]+\\r?\\n")

    /**
     * Shows an error dialog without blocking the caller.
     *
     * @param message the user-facing message
     */
    private fun showError(message: String) {
        DialogDisplayer.getDefault().notifyLater(
            NotifyDescriptor.Message(message, NotifyDescriptor.ERROR_MESSAGE),
        )
    }
}
