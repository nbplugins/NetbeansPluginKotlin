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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaPushMembersDownComputer
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtClass
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Integration tests for IDEA's K2 Push Members Down engine through its NetBeans adapter. */
class KaPushMembersDownComputerTest : KotlinTestCase("KaPushMembersDownComputerTest", "pushMembersDown") {
    /** Pushes a concrete method into direct subclasses in separate Kotlin source files. */
    fun testApply_concreteMember_movesToEveryDirectSubclass() {
        val fixture = createFixture() ?: return
        try {
            val computer = KaPushMembersDownComputer(fixture.sourceFile, fixture.base.textOffset)
            val discovery = computer.discover() as? KaPushMembersDownComputer.Discovery.Ready ?: return
            val greet = discovery.members.single { it.presentation.contains("greet") }

            val result = computer.apply(setOf(greet.offset), emptySet())

            assertTrue("Expected Push Members Down success, got $result", result is KaPushMembersDownComputer.Apply.Success)
            assertFalse("Source member must be removed:\n${fixture.sourceFile.text}", fixture.sourceFile.text.contains("fun greet"))
            val firstChild = fixture.session.getKtFileForPath(fixture.firstChildPath) ?: return
            val secondChild = fixture.session.getKtFileForPath(fixture.secondChildPath) ?: return
            assertTrue("First child must receive method:\n${firstChild.text}", firstChild.text.contains("fun greet(): String = \"hello\""))
            assertTrue("Second child must receive method:\n${secondChild.text}", secondChild.text.contains("fun greet(): String = \"hello\""))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /**
     * Keeps a pre-existing child implementation overridable after its source super member is removed.
     *
     * `override` makes a Kotlin member implicitly open. Push Down must therefore add explicit `open`
     * when it removes that modifier from Middle's member while Leaf still overrides it.
     */
    fun testApply_existingOverrideBecomesOpenForTransitiveOverride() {
        val fixture = createOverrideChainFixture() ?: return
        try {
            val computer = KaPushMembersDownComputer(fixture.sourceFile, fixture.root.textOffset)
            val discovery = computer.discover() as? KaPushMembersDownComputer.Discovery.Ready ?: return
            val ping = discovery.members.single { it.presentation.contains("ping") }

            val result = computer.apply(setOf(ping.offset), emptySet())

            assertTrue("Expected Push Members Down success, got $result", result is KaPushMembersDownComputer.Apply.Success)
            assertFalse("Root member must be removed:\n${fixture.sourceFile.text}", fixture.sourceFile.text.contains("fun ping"))
            val middle = fixture.session.getKtFileForPath(fixture.middlePath) ?: return
            val leaf = fixture.session.getKtFileForPath(fixture.leafPath) ?: return
            assertTrue("Middle must remain overridable:\n${middle.text}", middle.text.contains("open fun ping(): Int = 1"))
            assertFalse("Middle must no longer override Root:\n${middle.text}", middle.text.contains("override fun ping"))
            assertTrue("Leaf must retain its override:\n${leaf.text}", leaf.text.contains("override fun ping(): Int = 2"))
            assertNoErrors(middle, leaf)
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Retains an abstract source declaration when the selected member is pushed as abstract. */
    fun testApply_makeAbstract_keepsAbstractSourceDeclaration() {
        val fixture = createFixture() ?: return
        try {
            val computer = KaPushMembersDownComputer(fixture.sourceFile, fixture.base.textOffset)
            val discovery = computer.discover() as? KaPushMembersDownComputer.Discovery.Ready ?: return
            val greet = discovery.members.single { it.presentation.contains("greet") }

            val result = computer.apply(setOf(greet.offset), setOf(greet.offset))

            assertTrue("Expected Push Members Down success, got $result", result is KaPushMembersDownComputer.Apply.Success)
            assertTrue("Source must remain abstract:\n${fixture.sourceFile.text}",
                fixture.sourceFile.text.contains("abstract fun greet(): String"))
            val firstChild = fixture.session.getKtFileForPath(fixture.firstChildPath) ?: return
            assertTrue("First child must receive override:\n${firstChild.text}",
                firstChild.text.contains("override fun greet(): String = \"hello\""))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Creates a three-file standalone K2 session with a base class and two direct inheritors. */
    private fun createFixture(): Fixture? {
        val stdlib = findKotlinStdlib() ?: return null
        val directory = Files.createTempDirectory("nbkotlin-push-members-down")
        val basePath = directory.resolve("Base.kt")
        val firstChildPath = directory.resolve("FirstChild.kt")
        val secondChildPath = directory.resolve("SecondChild.kt")
        Files.writeString(basePath, "package pushdown\n\nopen class Base {\n    fun greet(): String = \"hello\"\n}\n")
        Files.writeString(firstChildPath, "package pushdown\n\nclass FirstChild : Base()\n")
        Files.writeString(secondChildPath, "package pushdown\n\nclass SecondChild : Base()\n")
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "push-members-down-integration",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(directory),
        )
        val sourceFile = session.getKtFileForPath(basePath.toString()) ?: return null
        val base = sourceFile.declarations.filterIsInstance<KtClass>().single { it.name == "Base" }
        return Fixture(directory, session, sourceFile, base, basePath.toString(), firstChildPath.toString(), secondChildPath.toString())
    }

    /** Creates a three-level override chain which must remain valid after Push Down. */
    private fun createOverrideChainFixture(): OverrideChainFixture? {
        val stdlib = findKotlinStdlib() ?: return null
        val directory = Files.createTempDirectory("nbkotlin-push-members-down-overrides")
        val rootPath = directory.resolve("Root.kt")
        val middlePath = directory.resolve("Middle.kt")
        val leafPath = directory.resolve("Leaf.kt")
        Files.writeString(rootPath, "package pushdown\n\nopen class Root {\n    open fun ping(): Int = 0\n}\n")
        Files.writeString(middlePath, "package pushdown\n\nopen class Middle : Root() {\n    override fun ping(): Int = 1\n}\n")
        Files.writeString(leafPath, "package pushdown\n\nclass Leaf : Middle() {\n    override fun ping(): Int = 2\n}\n")
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "push-members-down-overrides",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(directory),
        )
        val sourceFile = session.getKtFileForPath(rootPath.toString()) ?: return null
        val root = sourceFile.declarations.filterIsInstance<KtClass>().single { it.name == "Root" }
        return OverrideChainFixture(directory, session, sourceFile, root, middlePath.toString(), leafPath.toString())
    }

    /** Verifies that the mutated override chain still has no compiler-level K2 errors. */
    @OptIn(KaExperimentalApi::class)
    private fun assertNoErrors(vararg files: org.jetbrains.kotlin.psi.KtFile) {
        val errors = files.flatMap { file ->
            analyze(file) {
                file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                    .filter { it.severity == KaSeverity.ERROR }
                    .map { it.factoryName }
            }
        }
        assertTrue("Push Down must leave a compilable override chain, got: $errors", errors.isEmpty())
    }

    /** Finds the standard library JAR required by standalone Analysis API fixture sessions. */
    private fun findKotlinStdlib(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Test fixture state retained for the transitive-override Push Down regression. */
    private data class OverrideChainFixture(
        val directory: Path,
        val session: KotlinAnalysisAPISession,
        val sourceFile: org.jetbrains.kotlin.psi.KtFile,
        val root: KtClass,
        val middlePath: String,
        val leafPath: String,
    )

    /** Test fixture state retained for one Push Members Down invocation. */
    private data class Fixture(
        val directory: Path,
        val session: KotlinAnalysisAPISession,
        val sourceFile: org.jetbrains.kotlin.psi.KtFile,
        val base: KtClass,
        val basePath: String,
        val firstChildPath: String,
        val secondChildPath: String,
    )
}
