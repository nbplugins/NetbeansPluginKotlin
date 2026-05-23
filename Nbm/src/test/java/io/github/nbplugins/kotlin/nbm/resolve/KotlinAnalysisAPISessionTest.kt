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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinAnalysisAPISession].
 *
 * Test class structure mirrors the source class (MVC service layer), as required by
 * the project coding standards. All public methods of [KotlinAnalysisAPISession] have
 * at least one corresponding test.
 */
class KotlinAnalysisAPISessionTest : KotlinTestCase("K2 Analysis API session", "diagnostics") {

    /**
     * Clears the session cache after each test so that shared mutable state (PSI trees,
     * K2 FIR caches) from one test cannot bleed into the next.
     */
    override fun tearDown() {
        KotlinAnalysisAPISession.disposeAll()
        super.tearDown()
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.getSession] returns a non-null wrapper
     * and that the underlying [org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession]
     * is accessible.
     */
    fun testSessionCreates() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        assertNotNull("KotlinAnalysisAPISession must not be null", wrapper)
        assertNotNull("StandaloneAnalysisAPISession must not be null", wrapper.session)
    }

    /**
     * Verifies that successive calls to [KotlinAnalysisAPISession.getSession] for the same
     * project return the identical cached instance (no re-creation).
     */
    fun testSessionIsCached() {
        val s1 = KotlinAnalysisAPISession.getSession(project)
        val s2 = KotlinAnalysisAPISession.getSession(project)
        assertSame("getSession must return the cached instance", s1, s2)
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.disposeAll] clears the cache, so that
     * the next [KotlinAnalysisAPISession.getSession] call returns a fresh instance.
     */
    fun testDisposeAllClearsCache() {
        val s1 = KotlinAnalysisAPISession.getSession(project)
        KotlinAnalysisAPISession.disposeAll()
        val s2 = KotlinAnalysisAPISession.getSession(project)
        assertNotSame("After disposeAll, a new instance must be created", s1, s2)
    }

    /**
     * Smoke test: runs K2 diagnostics analysis on an existing test file.
     * Verifies that [analyze] completes without throwing and returns a non-null collection.
     *
     * Uses a KtFile from the K2 session's own [StandaloneAnalysisAPISession.modulesWithFiles]
     * rather than a K1 KtFile; the top-level [analyze] function requires a KtFile that
     * belongs to the K2 project.
     */
    @OptIn(KaExperimentalApi::class)
    fun testDiagnosticsAnalysisRunsWithoutException() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull("checkTypeMismatch.kt must be in the K2 session's source module", ktFile)

        val diagnostics = analyze(ktFile!!) {
            ktFile.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
        }
        assertNotNull("Diagnostics collection must not be null", diagnostics)
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.getKtFileForPath] returns the K2 [KtFile]
     * when a source file with the given path is registered in the session's source module.
     */
    fun testGetKtFileForPath_returnsFileForRegisteredSource() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        // Find any registered K2 KtFile to get a known-good path
        val anyK2File = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("Session must have at least one registered KtFile", anyK2File)

        val path = anyK2File!!.virtualFile?.path
        assertNotNull("K2 KtFile must have a virtualFile path", path)

        val result = wrapper.getKtFileForPath(path!!)
        assertNotNull("getKtFileForPath must return the KtFile for a registered path", result)
        assertEquals("Returned KtFile must have the requested path", path, result!!.virtualFile?.path)
    }

    /**
     * Verifies that JDK types (java.lang.Exception, java.io.Serializable, etc.) are accessible
     * in a K2 session that has no project binary JARs — only the JDK SDK module.
     *
     * Before the fix, `buildKtSdkModule` was absent, so JDK entries from the boot classpath
     * (which are `jrt:/` URLs on Java 9+, not `.jar` files) were silently dropped, causing
     * "Cannot access class 'java.lang.Exception'" false positives.
     */
    @OptIn(KaExperimentalApi::class)
    fun testJdkClassesAreVisibleInSession() {
        val sourceRoot = FileUtil.toFile(project.projectDirectory.getFileObject("src"))!!.toPath()
        val wrapper = KotlinAnalysisAPISession.createWithJars("test-jdk-check", emptyList(), listOf(sourceRoot))

        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkJdkTypesVisible.kt" }
        assertNotNull("checkJdkTypesVisible.kt must be in the K2 session's source module", ktFile)

        val diagnostics = analyze(ktFile!!) {
            ktFile.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
        }

        val jdkAccessErrors = diagnostics.filter { d ->
            val msg = d.defaultMessage
            msg.contains("Cannot access") && (msg.contains("java.lang") || msg.contains("java.io"))
        }
        assertTrue(
            "JDK types must be accessible — no 'Cannot access java.*' errors expected, " +
            "but got: ${jdkAccessErrors.map { it.defaultMessage }}",
            jdkAccessErrors.isEmpty()
        )
    }

    /**
     * Verifies that [CodeInsightContextManager] is accessible as a project service in the K2
     * standalone session.
     *
     * Platform 253 registers the service automatically via the standalone environment;
     * [CodeInsightContextManager.getInstance] must return non-null.
     */
    fun testCodeInsightContextManagerServiceRegistered() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val manager = CodeInsightContextManager.getInstance(wrapper.session.project)
        assertNotNull("CodeInsightContextManager service must be registered", manager)
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.getKtFileForPath] returns `null`
     * for a path that is not registered in the session.
     */
    fun testGetKtFileForPath_returnsNullForUnknownPath() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val result = wrapper.getKtFileForPath("/nonexistent/path/file.kt")
        assertNull("getKtFileForPath must return null for an unknown path", result)
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.updateFileContent] causes the session's
     * [KtFile] PSI to reflect the new text, including any new declarations.
     *
     * This is the key pre-condition for live semantic highlighting: the session must
     * be able to serve up-to-date PSI offsets when the user types unsaved changes.
     */
    fun testUpdateFileContent_reparsesPsi() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull("checkTypeMismatch.kt must be in the K2 session's source module", ktFile)
        val path = ktFile!!.virtualFile!!.path

        val originalText = ktFile.text
        val newText = originalText + "\nval injectedByTest = 42\n"

        wrapper.updateFileContent(path, newText)

        assertEquals("KtFile text must reflect the updated content after updateFileContent",
            newText, ktFile.text)
        assertNotNull("New declaration must appear in the reparsed PSI",
            ktFile.declarations.find { it.name == "injectedByTest" })
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.updateFileContent] is a no-op when the
     * supplied text equals the current document content (avoids unnecessary PSI reparsing).
     */
    fun testUpdateFileContent_noOpWhenTextUnchanged() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull(ktFile)
        val path = ktFile!!.virtualFile!!.path
        val originalText = ktFile.text

        // First call to load the document
        wrapper.updateFileContent(path, originalText)
        // Second call with same text — must not throw and must leave text unchanged
        wrapper.updateFileContent(path, originalText)

        assertEquals("Text must be unchanged after no-op update", originalText, ktFile.text)
    }

    /**
     * Verifies that [KotlinAnalysisAPISession.updateFileContent] is a no-op for an
     * unknown path (does not throw).
     */
    fun testUpdateFileContent_noOpForUnknownPath() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        wrapper.updateFileContent("/nonexistent/path/file.kt", "val x = 1")
        // No exception expected
    }

    /**
     * Verifies that [org.jetbrains.kotlin.analysis.api.analyze] succeeds after
     * [KotlinAnalysisAPISession.updateFileContent] — i.e., the K2 FIR cache is properly
     * invalidated so that the re-resolved FIR can map the new PSI nodes.
     *
     * Before the fix, [updateFileContent] used a PSI tree transplant without invalidating
     * the FIR cache, causing "No fir element was found for KtNamedFunction" on the next
     * [analyze] call.
     */
    @OptIn(KaExperimentalApi::class)
    fun testUpdateFileContent_analyzeSucceedsAfterUpdate() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull("checkTypeMismatch.kt must be in the K2 session's source module", ktFile)
        val path = ktFile!!.virtualFile!!.path

        val newText = ktFile.text + "\nval addedByTest = 99\n"
        wrapper.updateFileContent(path, newText)

        // Must not throw "No fir element was found for ..."
        val diagnostics = runCatching {
            analyze(ktFile) {
                ktFile.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
            }
        }
        assertTrue(
            "analyze {} must succeed after updateFileContent; error: ${diagnostics.exceptionOrNull()}",
            diagnostics.isSuccess
        )
        assertNotNull("Diagnostics collection must not be null", diagnostics.getOrNull())
    }

    /**
     * Verifies that repeated [KotlinAnalysisAPISession.updateFileContent] + [analyze] cycles keep
     * succeeding. Each update clears the K2 FIR caches (LLFirSessionCache source storage +
     * KaFirSessionProvider) so the session is rebuilt from the current PSI every time; a stale
     * cache from a previous edit would resurface "No fir element was found".
     */
    @OptIn(KaExperimentalApi::class)
    fun testUpdateFileContent_analyzeSucceedsAcrossRepeatedUpdates() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull("checkTypeMismatch.kt must be in the K2 session's source module", ktFile)
        val path = ktFile!!.virtualFile!!.path
        val baseText = ktFile.text

        repeat(3) { i ->
            wrapper.updateFileContent(path, baseText + "\nval roundTrip$i = $i\n")
            val result = runCatching {
                analyze(ktFile) {
                    ktFile.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                }
            }
            assertTrue(
                "analyze {} must succeed on update cycle #$i; error: ${result.exceptionOrNull()}",
                result.isSuccess
            )
        }
    }
}
