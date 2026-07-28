/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaPullMembersUpComputer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for Kotlin Pull Members Up candidate discovery. */
class KaPullMembersUpComputerTest : KotlinTestCase("KaPullMembersUpComputerTest", "pullMembersUp") {
    /** Discovers direct Kotlin supertypes and members from a child-class caret. */
    fun testDiscover_childClass_returnsTargetAndMemberCandidates() {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("simple")?.getFileObject("file.kt") ?: return
        val source = file.asText()
        val caret = source.indexOf("Child").also { if (it < 0) return }
        val ktFile = session.getKtFileForPath(file.path) ?: return

        val result = KaPullMembersUpComputer(ktFile, caret).discover()

        assertTrue("Expected Pull Members Up candidates, got $result", result is KaPullMembersUpComputer.Discovery.Ready)
        result as KaPullMembersUpComputer.Discovery.Ready
        assertEquals("Expected Base target", "Base", result.targets.single().name)
        assertTrue("Expected greet candidate", result.members.any { it.presentation.contains("greet") })
    }

    /** Rejects a caret in a class without a Kotlin supertype. */
    fun testDiscover_classWithoutSupertype_isNotApplicable() {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("noTarget")?.getFileObject("file.kt") ?: return
        val ktFile = session.getKtFileForPath(file.path) ?: return

        val result = KaPullMembersUpComputer(ktFile, 0).discover()

        assertSame(KaPullMembersUpComputer.Discovery.NotApplicable, result)
    }

    /** Moves a selected member into a superclass located in another Kotlin file. */
    fun testApply_crossFileTarget_movesSelectedMember() {
        val fixture = createCrossFileFixture() ?: return
        try {
            val discovery = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset).discover()
                as? KaPullMembersUpComputer.Discovery.Ready ?: return
            val greet = discovery.members.single { it.presentation.contains("greet") }
            val request = discovery.toRequest(discovery.targets.single(), setOf(greet.offset), emptySet())

            val result = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset, fixture.targetFile).apply(request)

            assertTrue("Expected Pull Members Up to succeed, got $result", result is KaPullMembersUpComputer.Apply.Success)
            result as KaPullMembersUpComputer.Apply.Success
            assertTrue("Expected target member, got:\n${result.targetText}", result.targetText.contains("fun greet(): String = \"hello\""))
            assertFalse("Expected source member to move, got:\n${result.sourceText}", result.sourceText.contains("fun greet(): String = \"hello\""))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Leaves an abstract declaration in the target and an override implementation in the source. */
    fun testApply_abstractMember_keepsOverrideInSource() {
        val fixture = createCrossFileFixture() ?: return
        try {
            val discovery = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset).discover()
                as? KaPullMembersUpComputer.Discovery.Ready ?: return
            val greet = discovery.members.single { it.presentation.contains("greet") }
            val request = discovery.toRequest(discovery.targets.single(), setOf(greet.offset), setOf(greet.offset))

            val result = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset, fixture.targetFile).apply(request)

            assertTrue("Expected Pull Members Up to succeed, got $result", result is KaPullMembersUpComputer.Apply.Success)
            result as KaPullMembersUpComputer.Apply.Success
            assertTrue("Expected abstract target declaration, got:\n${result.targetText}", result.targetText.contains("abstract fun greet(): String"))
            assertTrue("Expected source override implementation, got:\n${result.sourceText}", result.sourceText.contains("override fun greet(): String = \"hello\""))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Applies concrete and abstract choices independently for members selected in one request. */
    fun testApply_mixedAbstractAndConcreteMembers_preservesOnlyAbstractOverride() {
        val fixture = createCrossFileFixture("""
            class Child : Base() {
                fun concrete(): String = "concrete"
                fun abstracted(): String = "abstracted"
            }
        """.trimIndent()) ?: return
        try {
            val discovery = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset).discover()
                as? KaPullMembersUpComputer.Discovery.Ready ?: return
            val concrete = discovery.members.single { it.presentation.contains("concrete") }
            val abstracted = discovery.members.single { it.presentation.contains("abstracted") }
            val request = discovery.toRequest(
                discovery.targets.single(),
                setOf(concrete.offset, abstracted.offset),
                setOf(abstracted.offset),
            )

            val result = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset, fixture.targetFile).apply(request)

            assertTrue("Expected Pull Members Up to succeed, got $result", result is KaPullMembersUpComputer.Apply.Success)
            result as KaPullMembersUpComputer.Apply.Success
            assertTrue("Expected concrete target method, got:\n${result.targetText}", result.targetText.contains("fun concrete(): String = \"concrete\""))
            assertTrue("Expected abstract target method, got:\n${result.targetText}", result.targetText.contains("abstract fun abstracted(): String"))
            assertFalse("Expected concrete method to leave source, got:\n${result.sourceText}", result.sourceText.contains("fun concrete(): String = \"concrete\""))
            assertTrue("Expected abstract override to remain in source, got:\n${result.sourceText}", result.sourceText.contains("override fun abstracted(): String = \"abstracted\""))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Moves a property and two functions as separate declarations in one K2 Pull Members Up request. */
    fun testApply_propertyAndFunctions_movesSeparateMembers() {
        val fixture = createCrossFileFixture("""
            class Child : Base() {
                val answer = 42
                fun greet1(): String = "hello1"
                fun greet2(): String = "hello2"
            }
        """.trimIndent()) ?: return
        try {
            val discovery = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset).discover()
                as? KaPullMembersUpComputer.Discovery.Ready ?: return
            val selected = discovery.members.filter {
                it.presentation.contains("answer") || it.presentation.contains("greet1") || it.presentation.contains("greet2")
            }
            assertEquals("Expected property and function candidates", 3, selected.size)
            val request = discovery.toRequest(discovery.targets.single(), selected.mapTo(mutableSetOf()) { it.offset }, emptySet())

            val result = KaPullMembersUpComputer(fixture.sourceFile, fixture.child.textOffset, fixture.targetFile).apply(request)

            assertTrue("Expected Pull Members Up to succeed, got $result", result is KaPullMembersUpComputer.Apply.Success)
            result as KaPullMembersUpComputer.Apply.Success
            assertTrue("Expected target property, got:\n${result.targetText}", result.targetText.contains("val answer = 42"))
            assertTrue("Expected first target function, got:\n${result.targetText}", result.targetText.contains("fun greet1(): String = \"hello1\""))
            assertTrue("Expected second target function, got:\n${result.targetText}", result.targetText.contains("fun greet2(): String = \"hello2\""))
            assertTrue(
                "Expected a separator between the property and first function, got:\n${result.targetText}",
                Regex("val answer = 42\\R\\s*fun greet1\\(\\): String = \\\"hello1\\\"").containsMatchIn(result.targetText),
            )
            assertTrue(
                "Expected a separator between both functions, got:\n${result.targetText}",
                Regex("fun greet1\\(\\): String = \\\"hello1\\\"\\R\\s*fun greet2\\(\\): String = \\\"hello2\\\"").containsMatchIn(result.targetText),
            )
            val parsedTarget = KotlinFormatterUtils.createPsiFactory(project).createFile("Base.kt", result.targetText)
            val errors = PsiTreeUtil.collectElementsOfType(parsedTarget, PsiErrorElement::class.java)
            assertTrue(
                "Expected syntactically valid target, got errors ${errors.map(PsiErrorElement::getErrorDescription)} in:\n${result.targetText}",
                errors.isEmpty(),
            )
            val declarations = parsedTarget.declarations.filterIsInstance<KtClass>().single { it.name == "Base" }.declarations
            assertEquals("Expected one moved property", listOf("answer"), declarations.filterIsInstance<KtProperty>().map(KtProperty::getName))
            assertEquals("Expected two moved functions", listOf("greet1", "greet2"), declarations.filterIsInstance<KtNamedFunction>().map(KtNamedFunction::getName))
            assertFalse("Expected first source function to move, got:\n${result.sourceText}", result.sourceText.contains("fun greet1(): String = \"hello1\""))
            assertFalse("Expected second source function to move, got:\n${result.sourceText}", result.sourceText.contains("fun greet2(): String = \"hello2\""))
            assertFalse("Expected source property to move, got:\n${result.sourceText}", result.sourceText.contains("val answer = 42"))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Reports an existing target member before a mutation can be requested. */
    fun testCheckConflicts_targetMemberWithSameName_reportsConflict() {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("conflict")?.getFileObject("file.kt") ?: return
        val ktFile = session.getKtFileForPath(file.path) ?: return
        val child = ktFile.declarations.filterIsInstance<KtClass>().single { it.name == "Child" }
        val discovery = KaPullMembersUpComputer(ktFile, child.textOffset).discover()
            as? KaPullMembersUpComputer.Discovery.Ready ?: return
        val greet = discovery.members.single { it.presentation.contains("greet") }

        val result = KaPullMembersUpComputer(ktFile, child.textOffset).checkConflicts(
            discovery.toRequest(discovery.targets.single(), setOf(greet.offset), emptySet()),
        )

        assertTrue("Expected a conflict, got $result", result is KaPullMembersUpComputer.ConflictCheck.Conflicts)
        result as KaPullMembersUpComputer.ConflictCheck.Conflicts
        assertTrue("Expected target-member collision", result.items.any { it.message.contains("already contains") })
    }

    /** Creates a standalone two-file source hierarchy for real K2 Pull Members Up mutation tests. */
    private fun createCrossFileFixture(
        childDeclaration: String = """
            class Child : Base() {
                fun greet(): String = "hello"
            }
        """.trimIndent(),
    ): CrossFileFixture? {
        val stdlib = findKotlinStdlib() ?: return null
        val directory = Files.createTempDirectory("nbkotlin-pull-members-up")
        val basePath = directory.resolve("Base.kt")
        val childPath = directory.resolve("Child.kt")
        Files.writeString(basePath, "package pullup\n\nopen class Base\n")
        Files.writeString(childPath, "package pullup\n\n$childDeclaration\n")
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "pull-members-up-integration",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(directory),
        )
        val sourceFile = session.getKtFileForPath(childPath.toString()) ?: return null
        val targetFile = session.getKtFileForPath(basePath.toString()) ?: return null
        val child = sourceFile.declarations.filterIsInstance<KtClass>().single { it.name == "Child" }
        return CrossFileFixture(directory, sourceFile, targetFile, child)
    }

    /** Finds the Kotlin standard-library JAR needed by standalone Analysis API fixtures. */
    private fun findKotlinStdlib(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Holds the two PSI files and source child used by the cross-file mutation fixture. */
    private data class CrossFileFixture(
        /** Temporary directory containing both Kotlin files. */
        val directory: Path,
        /** PSI file containing the child source class. */
        val sourceFile: KtFile,
        /** PSI file containing the selected target superclass. */
        val targetFile: KtFile,
        /** Child class from [sourceFile]. */
        val child: KtClass,
    )
}
