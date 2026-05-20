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
     * Verifies that [CodeInsightContextManager] is registered as a project service in the K2
     * standalone session and returns `false` from [CodeInsightContextManager.isSharedSourceSupportEnabled].
     *
     * Without this registration, [com.intellij.psi.impl.file.PsiPackageImpl.getCachedClassesByName]
     * throws [IllegalStateException] during Java-type resolution, which prevents semantic highlighting.
     */
    fun testCodeInsightContextManagerServiceRegistered() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val manager = CodeInsightContextManager.getInstance(wrapper.session.project)
        assertNotNull("CodeInsightContextManager service must be registered", manager)
        assertFalse(
            "isSharedSourceSupportEnabled must return false in standalone session",
            manager.isSharedSourceSupportEnabled
        )
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
}
