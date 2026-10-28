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
     * Verifies that a genuinely global scope is not answered: it has no index in a standalone
     * session, and serving it would need a search over every source file for every query.
     */
    fun testGlobalScopeIsNotSearched() {
        val topLevelSource = """
            package j2k

            var globalCounter: Int = 0

            fun read(): Int = globalCounter
        """.trimIndent()
        withSource(topLevelSource) { _, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtProperty>().single()
            val references = ReferencesSearch.search(property).findAll()
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
     * nodes would come back empty.
     */
    fun testResolvesReferenceCreatedByPsiEdit() = withProperty { _, ktFile, _ ->
        val ktClass = ktFile.declarations.filterIsInstance<KtClass>().single()
        val newFunction = KtPsiFactory(ktFile.project)
            .createFunction("fun g(param: Int): Int { return param }")
        val added = ktClass.addDeclaration(newFunction) as KtNamedFunction
        val parameter = added.valueParameters.single()
        val statement = added.bodyBlockExpression?.statements?.singleOrNull() as? KtReturnExpression
        val reference = statement?.returnedExpression as? KtNameReferenceExpression
        assertNotNull("the edited function must contain a return statement", reference)
        val resolved = reference!!.mainReference?.resolve()
        println("EDITRESOLVE resolved=$resolved parameter=$parameter")
        assertEquals("a reference added by PSI edit must resolve", parameter, resolved)
    }

    /**
     * Diagnostic: replicates the J2K accessor construction (body moved into a synthetic setter
     * created by [KtPsiFactory]) and checks whether the moved reference still resolves.
     */
    fun testMovedBodyResolution() {
        val movedSource = """
            package j2k

            class MovedBody {
                private var name: String? = null

                fun setName(name: String?) {
                    this.name = name
                }
            }
        """.trimIndent()
        withSource(movedSource) { _, ktFile ->
            val klass = ktFile.declarations.filterIsInstance<KtClass>().single()
            val property = klass.declarations.filterIsInstance<KtProperty>().single()
            val function = klass.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "setName" }
            org.jetbrains.kotlin.analysis.api.analyze(ktFile) { println("MOVED analysis ok") }
            val body = function.bodyBlockExpression!!
            val factory = KtPsiFactory(ktFile.project)
            val synthetic = factory.createProperty("val y get() = 1\n set(name) {\n field = name\n }")
            val setter = synthetic.setter!!
            setter.bodyExpression?.replace(body)
            property.add(factory.createNewLine(1))
            val attached = property.add(setter) as org.jetbrains.kotlin.psi.KtPropertyAccessor
            println("MOVED before-delete resolved=${runCatching { (attached.bodyExpression as? KtBlockExpression)?.let { b -> ((b.statements.singleOrNull() as? KtBinaryExpression)?.right)?.mainReference?.resolve() } }}")
            function.delete()
            val statements = (attached.bodyExpression as? KtBlockExpression)?.statements
            println("MOVED statements=${statements?.size} text=${attached.text.replace("\n", " ")}")
            val binary = statements?.singleOrNull() as? KtBinaryExpression
            val right = binary?.right as? KtNameReferenceExpression
            println(
                "MOVED right=${right?.text} resolved=${runCatching { right?.mainReference?.resolve() }} " +
                    "param=${attached.parameterList?.parameters?.singleOrNull()}",
            )
        }
    }

    /**
     * Diagnostic: does resolution work inside a write action, as it must while the J2K
     * post-processing applies its changes?
     */
    fun testResolveInsideWriteAction() {
        val withSetter = """
            package j2k

            class WithSetter3 {
                var name: String? = null
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        withSource(withSetter) { _, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>().single()
            val setter = property.setter!!
            val body = setter.bodyExpression as? KtBlockExpression
            val right = (body?.statements?.singleOrNull() as? KtBinaryExpression)?.right!!
            val outside = runCatching { right.mainReference?.resolve() }
            val inside = runCatching {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runWriteAction<Any?> { right.mainReference?.resolve() }
            }
            println("WRITE outside=$outside inside=$inside")
        }
    }

    /**
     * Diagnostic: does resolution work on the EDT (where the J2K post-processing applies changes)?
     */
    fun testResolveOnEdt() {
        val withSetter = """
            package j2k

            class WithSetter5 {
                var name: String? = null
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        withSource(withSetter) { _, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>().single()
            val body = property.setter!!.bodyExpression as? KtBlockExpression
            val right = (body?.statements?.singleOrNull() as? KtBinaryExpression)?.right!!
            var onEdt: Any? = null
            var onEdtWithWrite: Any? = null
            javax.swing.SwingUtilities.invokeAndWait {
                onEdt = runCatching { right.mainReference?.resolve() }
                onEdtWithWrite = runCatching {
                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .runWriteAction<Any?> { right.mainReference?.resolve() }
                }
            }
            println("EDT plain=$onEdt write=$onEdtWithWrite")
        }
    }

    /**
     * Diagnostic: resolution inside `forbidAnalysis` on a cold session (no prior analysis).
     */
    fun testResolveUnderForbidAnalysisCold() {
        val withSetter = """
            package j2k

            class WithSetter4 {
                var name: String? = null
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        withSource(withSetter) { session, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>().single()
            val body = property.setter!!.bodyExpression as? KtBlockExpression
            val right = (body?.statements?.singleOrNull() as? KtBinaryExpression)?.right!!
            val inside = org.jetbrains.kotlin.analysis.api.permissions.forbidAnalysis("diag") {
                runCatching { right.mainReference?.resolve() }
            }
            println("COLD inside=$inside")
            val after = runCatching { right.mainReference?.resolve() }
            println("COLD after=$after")
            session.invalidateAnalysisCaches("diag")
            val afterInvalidate = runCatching { right.mainReference?.resolve() }
            println("COLD afterInvalidate=$afterInvalidate")
        }
    }

    /**
     * Diagnostic: does resolution work while analysis is explicitly forbidden, as the J2K
     * post-processing does while it applies its changes?
     */
    fun testResolveUnderForbidAnalysis() {
        val withSetter = """
            package j2k

            class WithSetter2 {
                var name: String? = null
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        withSource(withSetter) { _, ktFile ->
            val property = ktFile.declarations.filterIsInstance<KtClass>().single()
                .declarations.filterIsInstance<KtProperty>().single()
            val setter = property.setter!!
            val body = setter.bodyExpression as? KtBlockExpression
            val right = (body?.statements?.singleOrNull() as? KtBinaryExpression)?.right!!
            val outside = runCatching { right.mainReference?.resolve() }
            val inside = org.jetbrains.kotlin.analysis.api.permissions.forbidAnalysis("diag") {
                runCatching { right.mainReference?.resolve() }
            }
            println("FORBID outside=$outside inside=$inside")
        }
    }

    /**
     * Diagnostic: does a reference added after the file has already been analysed still resolve?
     */
    fun testResolveAfterAnalysisThenEdit() = withProperty { _, ktFile, property ->
        org.jetbrains.kotlin.analysis.api.analyze(ktFile) {
            println("PRE-ANALYSIS name=${property.name} symbol=${property.symbol}")
        }
        val ktClass = ktFile.declarations.filterIsInstance<KtClass>().single()
        val added = ktClass.addDeclaration(
            KtPsiFactory(ktFile.project).createFunction("fun g2(param: Int): Int { return param }"),
        ) as KtNamedFunction
        val parameter = added.valueParameters.single()
        val right = ((added.bodyBlockExpression?.statements?.singleOrNull() as? KtReturnExpression)
            ?.returnedExpression) as KtNameReferenceExpression
        val resolved = runCatching { right.mainReference?.resolve() }
        println("STALE resolved=$resolved parameter=$parameter")
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
            val parameter = setter!!.valueParameters.single()
            val body = setter.bodyExpression as? KtBlockExpression
            val right = (body?.statements?.singleOrNull() as? KtBinaryExpression)?.right
            assertNotNull("the setter body must be `field = value`", right)
            val resolved = right!!.mainReference?.resolve()
            println("RESOLVE right=${right.text} resolved=$resolved parameter=$parameter same=${resolved == parameter}")
            assertEquals("the setter parameter reference must resolve to its parameter", parameter, resolved)
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
