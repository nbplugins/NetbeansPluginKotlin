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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.psi.util.PsiTreeUtil
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Tests [PsiDeepestSuperMethodsQueryExecutor] — the PSI-only stand-in for the
 * `com.intellij.deepestSuperMethodsSearch` extension point that `java-impl` normally provides.
 *
 * A standalone K2 session has no `plugin.xml`, so without this executor every
 * `PsiMethod.findDeepestSuperMethods()` call (used by the J2K engine to recognise
 * `equals`/`hashCode`/`toString` overrides) throws `Missing extension point`.
 */
class PsiDeepestSuperMethodsQueryExecutorTest : KotlinTestCase("PsiDeepestSuperMethodsQueryExecutorTest", "deepestSuperMethods") {

    /** Session over the fixture directory; created on first use and shared by the assertions. */
    private val session: KotlinAnalysisAPISession by lazy {
        KotlinAnalysisAPISession.createWithJars(
            moduleName = project.projectDirectory.name,
            binaryJars = emptyList(),
            sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
        )
    }

    /**
     * Parses a fixture file through the session's PSI manager.
     *
     * @param fileName name of the fixture file to parse
     * @return the Java file, or `null` when the session does not contain it
     */
    private fun javaFile(fileName: String = "withEquals.java"): PsiJavaFile? {
        val fileObject = dir.getFileObject(fileName) ?: return null
        val virtualFile = session.fileMap[fileObject.path] ?: return null
        return runReadAction<PsiJavaFile?> {
            PsiManager.getInstance(session.session.project).findFile(virtualFile) as? PsiJavaFile
        }
    }

    /**
     * Returns the method with the given name from a fixture file.
     *
     * @param name method name
     * @param fileName fixture file declaring the method
     * @return the method, or `null` when the fixture does not declare it
     */
    private fun methodNamed(name: String, fileName: String = "withEquals.java"): PsiMethod? =
        javaFile(fileName)?.let { file ->
            runReadAction {
                PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).firstOrNull { it.name == name }
            }
        }

    /** An `equals` override resolves to the declaration in `java.lang.Object`. */
    fun testEqualsOverrideResolvesToObjectEquals() {
        val method = methodNamed("equals") ?: return
        val deepest = runReadAction { PsiSuperMethodImplUtil.findDeepestSuperMethods(method) }
        assertEquals("deepest super methods of equals", 1, deepest.size)
        val deepestMethod = deepest[0]
        assertEquals("equals", deepestMethod.name)
        assertEquals("java.lang.Object", runReadAction { deepestMethod.containingClass?.qualifiedName })
    }

    /**
     * An override that passes through an intermediate implementation still resolves to `Object`.
     *
     * `WithIntermediate.equals` overrides `Middle.equals`, which overrides `Object.equals`. The
     * intermediate declaration is not a deepest super method itself, so the search has to keep
     * walking up its chain instead of discarding it.
     */
    fun testEqualsThroughIntermediateClassResolvesToObjectEquals() {
        val method = methodNamed("equals", "withIntermediate.java") ?: return
        val deepest = runReadAction { PsiSuperMethodImplUtil.findDeepestSuperMethods(method) }
        assertEquals("deepest super methods of the inherited equals", 1, deepest.size)
        assertEquals(
            "java.lang.Object",
            runReadAction { deepest[0].containingClass?.qualifiedName },
        )
    }

    /** The executor walks through intermediate declarations as well. */
    fun testExecutorWalksThroughIntermediateClass() {
        val method = methodNamed("equals", "withIntermediate.java") ?: return
        val reported = mutableListOf<PsiMethod>()
        val finished = runReadAction {
            PsiDeepestSuperMethodsQueryExecutor().execute(method) {
                reported += it
                true
            }
        }
        assertTrue("executor must run to completion", finished)
        assertEquals("reported methods", 1, reported.size)
        assertEquals("java.lang.Object", runReadAction { reported[0].containingClass?.qualifiedName })
    }

    /** A method that overrides nothing has no deepest super methods. */
    fun testMethodWithoutSuperMethods() {
        val method = methodNamed("noSuperMethod") ?: return
        val deepest = runReadAction { PsiSuperMethodImplUtil.findDeepestSuperMethods(method) }
        assertTrue("noSuperMethod must not have super methods", deepest.isEmpty())
    }

    /** The executor reports the `java.lang.Object` declaration to a consumer that accepts it. */
    fun testExecutorReportsDeepestMethod() {
        val method = methodNamed("equals") ?: return
        val reported = mutableListOf<PsiMethod>()
        val finished = runReadAction {
            PsiDeepestSuperMethodsQueryExecutor().execute(method) {
                reported += it
                true
            }
        }
        assertTrue("executor must run to completion", finished)
        assertEquals("reported methods", 1, reported.size)
        assertEquals("java.lang.Object", runReadAction { reported[0].containingClass?.qualifiedName })
    }

    /** The executor stops as soon as the consumer rejects a method. */
    fun testExecutorStopsWhenConsumerReturnsFalse() {
        val method = methodNamed("equals") ?: return
        val finished = runReadAction { PsiDeepestSuperMethodsQueryExecutor().execute(method) { false } }
        assertFalse("consumer stopped, so execute must return false", finished)
    }
}
