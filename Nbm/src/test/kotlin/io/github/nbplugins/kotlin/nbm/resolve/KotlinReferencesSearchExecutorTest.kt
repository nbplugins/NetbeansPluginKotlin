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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import java.nio.file.Path
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinReferencesSearchExecutor].
 *
 * The executor exists because a standalone K2 session has no reference index: without it
 * `ReferencesSearch` returns nothing for Kotlin PSI, which silently breaks every consumer that
 * resolves usages — the J2K getters/setters post-processing above all. The tests therefore assert
 * through [ReferencesSearch] itself rather than through the executor, so they cover both the
 * search and its registration on the extension point.
 */
class KotlinReferencesSearchExecutorTest : KotlinTestCase("Kotlin references search", "j2k") {

    /** Kotlin source with one private field referenced from a method. */
    private val source = """
        package j2k

        class RefSearch {
            private var x: Int = 0

            fun f(): Int {
                return x
            }
        }
    """.trimIndent()

    /**
     * Verifies that a reference inside a file-wide [LocalSearchScope] resolves to the searched
     * property, which is what the J2K post-processing relies on.
     */
    fun testFindsReferenceInsideLocalScope() = withProperty { _, ktFile, property ->
        val references = ReferencesSearch.search(property, LocalSearchScope(ktFile), true).findAll()
        assertEquals("one usage of `x` expected", 1, references.size)
        assertEquals("x", references.single().element.text)
    }

    /**
     * Verifies that a scope narrowed to a single declaration is searched too — the J2K engine
     * passes the element rather than the whole file when it searches from inside a member.
     */
    fun testFindsReferenceInsideNarrowedScope() = withProperty { _, ktFile, property ->
        val ktClass = ktFile.declarations.filterIsInstance<KtClass>().single()
        val references = ReferencesSearch.search(property, LocalSearchScope(ktClass), true).findAll()
        assertEquals("one usage of `x` expected", 1, references.size)
    }

    /**
     * Verifies that a search without an explicit scope is still answered when the searched element
     * is local: the platform narrows the effective scope to the element's use scope, which for a
     * private member is a [LocalSearchScope], and the executor serves it.
     */
    fun testUsesElementUseScopeWhenNoScopeGiven() = withProperty { _, _, property ->
        val references = ReferencesSearch.search(property).findAll()
        assertEquals("one usage of `x` expected", 1, references.size)
        assertEquals("x", references.single().element.text)
    }

    /**
     * Verifies that a global scope is not answered: it has no index in a standalone session, and
     * serving it would need a search over every source file for every query.
     */
    fun testGlobalScopeIsNotSearched() {
        val topLevelSource = """
            package j2k

            var globalCounter: Int = 0

            fun read(): Int = globalCounter
        """.trimIndent()
        withSource(topLevelSource) { _, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtProperty>().single()
            val globalScope = GlobalSearchScope.allScope(ktFile.project)
            val references = ReferencesSearch.search(property, globalScope, true).findAll()
            assertTrue("global search must stay empty: $references", references.isEmpty())
        }
    }

    /**
     * Verifies that [KotlinReferencesSearchExecutor.execute] stops when the consumer stops and
     * reports that the search was cut short.
     */
    fun testStopsWhenConsumerStops() = withProperty { _, ktFile, property ->
        val parameters = ReferencesSearch.SearchParameters(property, LocalSearchScope(ktFile), true)
        var processed = 0
        val completed = KotlinReferencesSearchExecutor().execute(parameters) {
            processed++
            false
        }
        assertFalse("the consumer asked to stop, so the search must report false", completed)
        assertEquals("the consumer must be called exactly once", 1, processed)
    }

    /**
     * Verifies that references that resolve to another declaration are not reported: the search
     * must be resolution-based, not name-based.
     */
    fun testIgnoresReferencesToOtherDeclarations() {
        val twoFields = """
            package j2k

            class TwoFields {
                private var first: Int = 0
                private var second: Int = 0

                fun f(): Int {
                    return second
                }
            }
        """.trimIndent()
        withSource(twoFields) { _, ktFile ->
            val properties = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>()
            val first = properties.single { it.name == "first" }
            assertTrue(
                "`first` is never used, so the search must be empty",
                ReferencesSearch.search(first, LocalSearchScope(ktFile), true).findAll().isEmpty(),
            )
        }
    }

    /**
     * Verifies that a reference created by an in-place PSI edit still resolves after the edit.
     *
     * The J2K post-processing builds new declarations with `KtPsiFactory` and then analyses them;
     * if the analysis kept serving a snapshot of the pre-edit file, every resolution of the new
     * nodes would come back empty. The test freezes the file around the edit, exactly as the
     * converter does: that is the signal [NoOpPomModel] uses to drop the analysis caches after a
     * transaction.
     */
    fun testResolvesReferenceCreatedByPsiEdit() = withProperty { session, ktFile, _ ->
        val path = ktFile.virtualFile.path
        session.freezeFileContent(path)
        try {
            val ktClass = ktFile.declarations.filterIsInstance<KtClass>().single()
            val newFunction = KtPsiFactory(ktFile.project)
                .createFunction("fun g(param: Int): Int { return param }")
            val added = ktClass.addDeclaration(newFunction) as KtNamedFunction
            val parameter = added.valueParameters.single()
            val statement = added.bodyBlockExpression?.statements?.singleOrNull() as? KtReturnExpression
            val reference = statement?.returnedExpression as? KtNameReferenceExpression
            assertNotNull("the edited function must contain a return statement", reference)
            assertEquals("a reference added by PSI edit must resolve", parameter, reference!!.mainReference?.resolve())
        } finally {
            session.unfreezeFileContent(path)
        }
    }

    /**
     * Verifies that an edit made *after* the file has already been analysed also invalidates the
     * analysis — the sequence the J2K post-processing performs on a file it has just converted.
     */
    fun testResolveAfterAnalysisThenEdit() = withProperty { session, ktFile, property ->
        val path = ktFile.virtualFile.path
        session.freezeFileContent(path)
        try {
            org.jetbrains.kotlin.analysis.api.analyze(ktFile) { property.symbol }
            val ktClass = ktFile.declarations.filterIsInstance<KtClass>().single()
            val added = ktClass.addDeclaration(
                KtPsiFactory(ktFile.project).createFunction("fun g2(param: Int): Int { return param }"),
            ) as KtNamedFunction
            val parameter = added.valueParameters.single()
            val right = ((added.bodyBlockExpression?.statements?.singleOrNull() as? KtReturnExpression)
                ?.returnedExpression) as KtNameReferenceExpression
            assertEquals("the reference must resolve against the re-analysed file", parameter, right.mainReference?.resolve())
        } finally {
            session.unfreezeFileContent(path)
        }
    }

    /**
     * Verifies that a reference to a setter parameter resolves after the property PSI has been
     * edited in place — the pattern `KtPropertyAccessor.isRedundantSetter` depends on when the J2K
     * post-processing decides whether a converted accessor can be dropped.
     */
    fun testResolvesSetterParameterAfterPsiEdit() {
        val withSetter = """
            package j2k

            class WithSetter {
                private var name: String? = null
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        withSource(withSetter) { _, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>().single()
            val setter = property.setter
            assertNotNull("the setter must be parsed", setter)
            val parameter = setter!!.parameterList!!.parameters.single()
            val body = setter.bodyExpression as? KtBlockExpression
            val right = (body?.statements?.singleOrNull() as? KtBinaryExpression)?.right
            assertNotNull("the setter body must be `field = value`", right)
            assertEquals("the setter parameter reference must resolve to its parameter", parameter, right!!.mainReference?.resolve())
        }
    }

    /**
     * Runs [body] on the session's file together with the single `x` property of [source].
     *
     * Skips the test when `kotlin-stdlib` is missing from the local Maven repository: without it
     * no analysis session can be built and the search would not be exercised at all.
     *
     * @param body receives the session, the analysed file and its single property
     */
    private fun withProperty(body: (KotlinAnalysisAPISession, KtFile, KtProperty) -> Unit) =
        withSource(source) { session, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>().single()
            body(session, ktFile, property)
        }

    /**
     * Creates a session whose only Kotlin file contains [text] and passes both to [body].
     *
     * The file is created, frozen and unfrozen inside the test so the session never sees the empty
     * seed on disk; the same sequence the converter uses.
     *
     * @param text the Kotlin source to analyse
     * @param body receives the session and the parsed file
     */
    private fun withSource(
        text: String,
        body: (KotlinAnalysisAPISession, KtFile) -> Unit,
    ) {
        val stdlib = findStdlibJarOrNull() ?: return
        val kotlinFileObject = FileUtil.createData(dir, "referencesSearch.kt")
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = project.projectDirectory.name,
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
        )
        try {
            session.freezeFileContent(kotlinFileObject.path)
            try {
                session.updateFrozenFileContent(kotlinFileObject.path, text)
                val ktFile = session.getKtFileForPath(kotlinFileObject.path)
                assertNotNull("the session must register ${kotlinFileObject.name}", ktFile)
                body(session, ktFile!!)
            } finally {
                runCatching { session.unfreezeFileContent(kotlinFileObject.path) }
            }
        } finally {
            runCatching { kotlinFileObject.delete() }
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /**
     * Returns the Kotlin standard library matching the bundled compiler, if present.
     *
     * @return the JAR path, or `null` when no matching stdlib exists in `~/.m2`
     */
    private fun findStdlibJarOrNull(): Path? {
        val home = System.getProperty("user.home") ?: return null
        return STDLIB_VERSIONS.asSequence()
            .map { Path.of(home, ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/$it/kotlin-stdlib-$it.jar") }
            .firstOrNull { it.toFile().isFile }
    }

    private companion object {
        /** Stdlib versions to try, newest first; the first must match `kotlin.runtime.version`. */
        val STDLIB_VERSIONS = listOf("2.3.21", "2.2.21", "2.2.20", "2.1.21", "2.0.21")
    }
}
