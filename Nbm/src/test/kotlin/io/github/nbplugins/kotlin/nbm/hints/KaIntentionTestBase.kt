/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints

import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import utils.equalsWithoutSpaces
import utils.getCaret
import utils.getDocumentForFileObject
import java.nio.file.Path

/**
 * Shared test infrastructure for [KaApplicableIntention] subclasses.
 *
 * Provides [doTest] which:
 * 1. Loads the fixture `.kt` file and caret position from `.caret` companion.
 * 2. Obtains the K2-session [KtFile] from [KotlinAnalysisAPISession].
 * 3. Creates the intention via the supplied factory.
 * 4. Asserts [KaApplicableIntention.isApplicable] returns the expected value.
 * 5. If [checkImplement] is true, calls [KaApplicableIntention.implement] and verifies
 *    the document content against the `.after` companion file.
 *
 * @param name display name forwarded to [KotlinTestCase]
 * @param dir  resource directory name under `projForTest/src/`
 */
abstract class KaIntentionTestBase(name: String, dir: String) : KotlinTestCase(name, dir) {

    /**
     * Returns the K2-session [KtFile] for [path].
     *
     * If the project's cached [KotlinAnalysisAPISession] has no binary dependencies
     * (i.e. stdlib is absent from `project.properties`), falls back to creating a
     * temporary session that includes `kotlin-stdlib-1.9.25.jar` from the local Maven
     * repository (`~/.m2/repository/...`).  Returns null and prints a skip message
     * only when stdlib cannot be found even in the Maven repo.
     *
     * @param path absolute path to the `.kt` file on disk
     * @return K2-owned [KtFile], or null to skip the test
     */
    protected fun getKaKtFileOrSkip(path: String): KtFile? {
        var wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            // Try to locate kotlin-stdlib in the Maven local repository.
            val stdlibPath = findStdlibJarOrNull()
            if (stdlibPath == null) {
                println("${javaClass.simpleName}: skipping — K2 session has no dependencies and kotlin-stdlib not found in ~/.m2")
                return null
            }
            // Collect source roots from the cached session's internal module structure.
            val sourceRoots = wrapper.session.modulesWithFiles.values.flatten()
                .filterIsInstance<KtFile>()
                .mapNotNull { it.virtualFile?.path }
                .map { Path.of(it).parent }
                .distinct()
            wrapper = KotlinAnalysisAPISession.createWithJars(
                moduleName = project.projectDirectory.name,
                binaryJars = listOf(stdlibPath),
                sourceRoots = sourceRoots
            )
        }
        return wrapper.getKtFileForPath(path)
    }

    /**
     * Searches for `kotlin-stdlib-1.9.25.jar` in the local Maven repository.
     *
     * @return path to the JAR, or null if not present
     */
    private fun findStdlibJarOrNull(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val candidates = listOf(
            Path.of(home, ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.9.25/kotlin-stdlib-1.9.25.jar")
        )
        return candidates.firstOrNull { it.toFile().exists() }
    }

    /**
     * Runs an [KaApplicableIntention] test against the named fixture file.
     *
     * @param fileName       fixture file name (without extension)
     * @param factory        creates the intention given `(doc, kaKtFile, psi)`
     * @param applicable     expected result of [KaApplicableIntention.isApplicable]
     * @param checkImplement when true, call [KaApplicableIntention.implement] and diff against `.after`
     */
    protected fun doTest(
        fileName: String,
        applicable: Boolean = true,
        checkImplement: Boolean = true,
        factory: (
            doc: javax.swing.text.Document,
            kaKtFile: KtFile,
            psi: com.intellij.psi.PsiElement
        ) -> KaApplicableIntention
    ) {
        val file = dir.getFileObject("$fileName.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val caret = getCaret(getDocumentForFileObject(dir, "$fileName.caret"))
        assertNotNull(caret)

        val doc = getDocumentForFileObject(dir, "$fileName.kt")
        // psi must come from the K2 tree so that analyze(kaKtFile) can resolve its module.
        val psi = kaKtFile.findElementAt(caret) ?: run {
            println("${javaClass.simpleName}: no K2 PSI at caret for $fileName — skipping")
            return
        }

        val intention = factory(doc, kaKtFile, psi)
        assertEquals(applicable, intention.isApplicable(caret))

        if (applicable && checkImplement) {
            // Snapshot the document before implement() so we can restore it afterward.
            // This prevents K2 tests from leaving modified shared documents that would
            // break subsequent K1 tests running in the same JVM.
            val originalContent = doc.getText(0, doc.length)
            try {
                intention.implement()
                val afterText = dir.getFileObject("$fileName.after")?.asText() ?: return
                val actual = doc.getText(0, doc.length)
                if (!(actual equalsWithoutSpaces afterText)) {
                    println("$fileName ACTUAL  : ${actual.replace("\n", "\\n")}")
                    println("$fileName EXPECTED: ${afterText.replace("\n", "\\n")}")
                }
                assertTrue(
                    "Document content mismatch after implementing intention for $fileName",
                    actual equalsWithoutSpaces afterText
                )
            } finally {
                // Restore the document to its original content so other tests see the
                // unmodified fixture.
                val current = doc.getText(0, doc.length)
                if (current != originalContent) {
                    doc.remove(0, doc.length)
                    doc.insertString(0, originalContent, null)
                }
            }
        }
    }
}
