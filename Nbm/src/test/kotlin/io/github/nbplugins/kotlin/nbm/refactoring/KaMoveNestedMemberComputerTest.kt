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
import io.github.nbplugins.kotlin.refactoring.KaMoveNestedMemberComputer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Tests the portable standalone K2 contract for moving nested Kotlin declarations. */
class KaMoveNestedMemberComputerTest : KotlinTestCase("KaMoveNestedMemberComputerTest", "moveNestedMember") {

    /** Moves a nested class between two named Kotlin classes. */
    fun testMove_nestedClass_movesToTargetClass() {
        val fixture = createFixture(
            sourceBody = "class Nested(val value: Int)",
            targetBody = "",
        ) ?: return
        try {
            val result = fixture.computer("Nested").move(fixture.targetFile, fixture.target.textOffset)

            assertTrue("Expected successful nested-class move, got $result", result is KaMoveNestedMemberComputer.Apply.Success)
            result as KaMoveNestedMemberComputer.Apply.Success
            assertFalse("Source must no longer contain moved nested class:\n${result.sourceText}", result.sourceText.contains("class Nested"))
            assertTrue("Source closing brace must remain on its own line:\n${result.sourceText}", result.sourceText.contains("class Source {\n}"))
            assertTrue(
                "Target must contain the moved class inside its body:\n${result.targetText}",
                result.targetText.indexOf("class Target {") < result.targetText.indexOf("class Nested") &&
                    result.targetText.indexOf("class Nested") < result.targetText.lastIndexOf("}"),
            )
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Discovers named class/object targets other than the nested class's current owner. */
    fun testDiscoverTargets_nestedClass_listsEligibleNamedContainers() {
        val fixture = createFixture(
            sourceBody = "class Nested(val value: Int)",
            targetBody = "",
        ) ?: return
        try {
            val targets = fixture.computer("Nested").discoverTargets(listOf(fixture.sourceFile, fixture.targetFile))

            assertEquals(1, targets.size)
            assertEquals("Target", targets.single().presentation)
            assertEquals("nestedmove", targets.single().packageName)
            assertEquals(listOf("Target"), targets.single().containerPath)
            assertEquals(fixture.targetFile.virtualFile?.path, targets.single().filePath)
            assertEquals(fixture.target.textOffset, targets.single().offset)
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Discovers and populates a bodyless top-level target declared in the same Kotlin file. */
    fun testMove_nestedClass_populatesBodylessTargetInSameFile() {
        val stdlib = findKotlinStdlib() ?: return
        val directory = Files.createTempDirectory("nbkotlin-move-nested-member-bodyless-target")
        val sourcePath = directory.resolve("Source.kt")
        Files.writeString(
            sourcePath,
            "package nestedmove\n\nclass Source {\n    class Nested(val value: Int)\n}\n\nclass Target\n",
        )
        try {
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-nested-member-bodyless-target",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(directory),
            )
            val file = session.getKtFileForPath(sourcePath.toString()) ?: return
            val source = file.declarations.filterIsInstance<KtClass>().single { it.name == "Source" }
            val target = file.declarations.filterIsInstance<KtClass>().single { it.name == "Target" }
            val computer = KaMoveNestedMemberComputer(
                file,
                source.collectDescendantsOfType<KtNamedDeclaration>().first { it.name == "Nested" }.textOffset,
            )

            val candidates = computer.discoverTargets(listOf(file))
            assertEquals(listOf("Target"), candidates.map { it.presentation })

            val result = computer.move(file, target.textOffset)
            assertTrue("Expected successful move to a bodyless target, got $result", result is KaMoveNestedMemberComputer.Apply.Success)
            result as KaMoveNestedMemberComputer.Apply.Success
            assertTrue("Target must gain a body:\n${result.targetText}", result.targetText.contains("class Target {"))
            assertTrue(
                "Moved class must be inside Target's body:\n${result.targetText}",
                result.targetText.indexOf("class Target {") < result.targetText.indexOf("class Nested") &&
                    result.targetText.indexOf("class Nested") < result.targetText.lastIndexOf("}"),
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Discovers only class/object targets with exactly one companion for companion-member moves. */
    fun testDiscoverTargets_companionMember_requiresSingleTargetCompanion() {
        val fixture = createFixture(
            sourceBody = "companion object { fun greet(): String = \"hello\" }",
            targetBody = "companion object { }",
        ) ?: return
        try {
            val withoutCompanionPath = fixture.directory.resolve("NoCompanion.kt")
            Files.writeString(withoutCompanionPath, "package nestedmove\n\nclass NoCompanion { }\n")
            val withTwoCompanionsPath = fixture.directory.resolve("TwoCompanions.kt")
            Files.writeString(
                withTwoCompanionsPath,
                "package nestedmove\n\nclass TwoCompanions { companion object First { } companion object Second { } }\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-nested-member-target-discovery",
                binaryJars = listOf(findKotlinStdlib() ?: return),
                sourceRoots = listOf(fixture.directory),
            )
            val sourceFile = session.getKtFileForPath(fixture.directory.resolve("Source.kt").toString()) ?: return
            val targetFile = session.getKtFileForPath(fixture.directory.resolve("Target.kt").toString()) ?: return
            val noCompanionFile = session.getKtFileForPath(withoutCompanionPath.toString()) ?: return
            val twoCompanionsFile = session.getKtFileForPath(withTwoCompanionsPath.toString()) ?: return
            val source = sourceFile.declarations.filterIsInstance<KtClass>().single { it.name == "Source" }
            val computer = KaMoveNestedMemberComputer(
                sourceFile,
                source.collectDescendantsOfType<KtNamedDeclaration>().first { it.name == "greet" }.textOffset,
            )

            val targets = computer.discoverTargets(listOf(sourceFile, targetFile, noCompanionFile, twoCompanionsFile))

            assertEquals(listOf("Target"), targets.map { it.presentation })
            assertTrue(targets.single().requiresCompanionTarget)
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Moves a companion function into the selected target companion object. */
    fun testMove_companionFunction_movesToTargetCompanion() {
        val fixture = createFixture(
            sourceBody = "companion object { fun greet(): String = \"hello\" }",
            targetBody = "companion object { }",
        ) ?: return
        try {
            val result = fixture.computer("greet").move(fixture.targetFile, fixture.target.textOffset)

            assertTrue("Expected successful companion-function move, got $result", result is KaMoveNestedMemberComputer.Apply.Success)
            result as KaMoveNestedMemberComputer.Apply.Success
            assertFalse("Source must no longer contain moved function:\n${result.sourceText}", result.sourceText.contains("fun greet"))
            assertTrue("Target must contain moved function:\n${result.targetText}", result.targetText.contains("fun greet"))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Moves a companion property into the target companion object. */
    fun testMove_companionProperty_movesToTargetCompanion() {
        val fixture = createFixture(
            sourceBody = "companion object { val answer: Int = 42 }",
            targetBody = "companion object { }",
        ) ?: return
        try {
            val result = fixture.computer("answer").move(fixture.targetFile, fixture.target.textOffset)

            assertTrue("Expected successful companion-property move, got $result", result is KaMoveNestedMemberComputer.Apply.Success)
            result as KaMoveNestedMemberComputer.Apply.Success
            assertFalse("Source must no longer contain moved property:\n${result.sourceText}", result.sourceText.contains("val answer"))
            assertTrue("Target must contain moved property:\n${result.targetText}", result.targetText.contains("val answer"))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Reports a target member collision before mutating standalone K2 PSI. */
    fun testCheckConflicts_duplicateTargetMember_reportsConflict() {
        val fixture = createFixture(
            sourceBody = "class Nested",
            targetBody = "class Nested",
        ) ?: return
        try {
            val result = fixture.computer("Nested").checkConflicts(fixture.targetFile, fixture.target.textOffset)

            assertTrue("Expected target collision, got $result", result is KaMoveNestedMemberComputer.ConflictCheck.Conflicts)
            result as KaMoveNestedMemberComputer.ConflictCheck.Conflicts
            assertTrue("Expected a duplicate-member message, got ${result.messages}", result.messages.any { it.contains("Nested") })
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Rejects an ordinary instance member because relocating its receiver is not mechanically safe. */
    fun testCompute_instanceFunction_isNotApplicable() {
        val fixture = createFixture(sourceBody = "fun greet(): String = \"hello\"", targetBody = "") ?: return
        try {
            assertSame(KaMoveNestedMemberComputer.Outcome.NotApplicable, fixture.computer("greet").compute())
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Creates a standalone K2 project with source, target, and optional external usage files. */
    private fun createFixture(sourceBody: String, targetBody: String, usage: String? = null): Fixture? {
        val stdlib = findKotlinStdlib() ?: return null
        val directory = Files.createTempDirectory("nbkotlin-move-nested-member")
        val sourcePath = directory.resolve("Source.kt")
        val targetPath = directory.resolve("Target.kt")
        Files.writeString(sourcePath, "package nestedmove\n\nclass Source {\n    $sourceBody\n}\n")
        Files.writeString(targetPath, "package nestedmove\n\nclass Target {\n    $targetBody\n}\n")
        usage?.let { Files.writeString(directory.resolve("Usage.kt"), "package nestedmove\n\n$it\n") }
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "move-nested-member-integration",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(directory),
        )
        val sourceFile = session.getKtFileForPath(sourcePath.toString()) ?: return null
        val targetFile = session.getKtFileForPath(targetPath.toString()) ?: return null
        val source = sourceFile.declarations.filterIsInstance<KtClass>().single { it.name == "Source" }
        val target = targetFile.declarations.filterIsInstance<KtClass>().single { it.name == "Target" }
        return Fixture(directory, sourceFile, targetFile, source, target)
    }

    /** Finds the Kotlin standard library required by standalone Analysis API fixtures. */
    private fun findKotlinStdlib(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Holds standalone K2 source and target inputs for one narrow move request. */
    private data class Fixture(
        val directory: Path,
        val sourceFile: KtFile,
        val targetFile: KtFile,
        val source: KtClass,
        val target: KtClass,
    ) {
        /** Resolves one source declaration by name into the move computer. */
        fun computer(memberName: String): KaMoveNestedMemberComputer = KaMoveNestedMemberComputer(
            sourceFile,
            source.collectDescendantsOfType<KtNamedDeclaration>().first { it.name == memberName }.textOffset,
        )
    }
}
