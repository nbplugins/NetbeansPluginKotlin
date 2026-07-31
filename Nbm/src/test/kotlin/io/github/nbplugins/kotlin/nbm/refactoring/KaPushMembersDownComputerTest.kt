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

    /** Finds the standard library JAR required by standalone Analysis API fixture sessions. */
    private fun findKotlinStdlib(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

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
