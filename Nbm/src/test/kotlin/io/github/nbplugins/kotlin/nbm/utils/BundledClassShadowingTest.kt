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
package io.github.nbplugins.kotlin.nbm.utils

import org.netbeans.junit.NbTestCase
import java.io.File
import java.util.jar.JarFile

/**
 * Guards against a plugin module re-declaring a class that the bundled IntelliJ platform already
 * provides.
 *
 * NetBeans resolves module class-path entries in an order that lets a plugin JAR shadow a class
 * from a bundled JAR (this happened with `JavaResolveUtil` in `KotlinRefactoring`). A partial
 * stub used as such a shadow is invisible at compile time but breaks at runtime, because the
 * bundled K2 engine links against the whole original API — the observed symptom was
 * `NoSuchMethodError: JavaResolveUtil.substituteResults(...)`, an empty Navigator panel, and
 * missing error highlighting for Kotlin files that reference Java library classes.
 */
class BundledClassShadowingTest : NbTestCase("BundledClassShadowingTest") {

    /**
     * Verifies that the `KotlinRefactoring` module does not package its own copy of
     * [JAVA_RESOLVE_UTIL].
     *
     * The full era-193 implementation is supplied by
     * `netbeans-plugin-kotlin-compiler-cli-base` (a `provided` dependency), so any copy inside
     * the refactoring module is a shadowing regression.
     */
    fun testRefactoringModuleDoesNotShadowJavaResolveUtil() {
        val location = moduleLocation("io.github.nbplugins.kotlin.refactoring.KaRenameComputer")
        val containsClass = if (location.isDirectory) {
            File(location, JAVA_RESOLVE_UTIL).isFile
        } else {
            JarFile(location).use { jar -> jar.getEntry(JAVA_RESOLVE_UTIL) != null }
        }
        assertFalse(
            "KotlinRefactoring must not ship $JAVA_RESOLVE_UTIL — it shadows the full implementation " +
                "from netbeans-plugin-kotlin-compiler-cli-base and breaks the K2 engine. Found in: $location",
            containsClass
        )
    }

    /**
     * Verifies that the `JavaResolveUtil` visible at runtime exposes the method the bundled K2
     * engine links against.
     *
     * This is the exact call that failed with `NoSuchMethodError` when the partial stub was on
     * the class path.
     */
    fun testJavaResolveUtilExposesSubstituteResults() {
        val cls = Class.forName(JAVA_RESOLVE_UTIL_FQN)
        assertNotNull(
            "Runtime $JAVA_RESOLVE_UTIL_FQN must provide substituteResults(PsiJavaCodeReferenceElement, JavaResolveResult[])",
            cls.declaredMethods.firstOrNull { it.name == "substituteResults" && it.parameterCount == 2 }
        )
    }

    /**
     * Returns the code-source location (JAR file or class directory) of the module that provides
     * [className].
     *
     * The class is loaded without initialization, so no static initializer runs.
     */
    private fun moduleLocation(className: String): File =
        File(Class.forName(className, false, javaClass.classLoader).protectionDomain.codeSource.location.toURI())

    private companion object {
        private const val JAVA_RESOLVE_UTIL = "com/intellij/psi/impl/source/resolve/JavaResolveUtil.class"
        private const val JAVA_RESOLVE_UTIL_FQN = "com.intellij.psi.impl.source.resolve.JavaResolveUtil"
    }
}
