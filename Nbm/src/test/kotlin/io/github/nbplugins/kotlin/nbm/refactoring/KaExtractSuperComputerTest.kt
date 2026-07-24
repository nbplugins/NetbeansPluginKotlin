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
import io.github.nbplugins.kotlin.refactoring.ExtractSuperKind
import io.github.nbplugins.kotlin.refactoring.ExtractSuperRequest
import io.github.nbplugins.kotlin.refactoring.KaExtractSuperComputer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for Extract Interface/Extract Superclass source-class discovery. */
class KaExtractSuperComputerTest : KotlinTestCase("KaExtractSuperComputerTest", "extractSuper") {
    /** Discovers the class member at a caret positioned in its body. */
    fun testDiscover_insideClass_returnsMember() {
        val session = io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("simple")?.getFileObject("file.kt") ?: return
        val source = file.asText()
        val caret = source.indexOf("greet").also { if (it < 0) return }
        val ktFile = session.getKtFileForPath(file.path) ?: return

        val result = KaExtractSuperComputer(ktFile, caret).discover()

        assertTrue("Expected members for source class, got $result", result is KaExtractSuperComputer.Discovery.Ready)
        result as KaExtractSuperComputer.Discovery.Ready
        assertEquals("Greeter", result.sourceName)
        assertTrue("Expected greet candidate", result.members.any { it.presentation.contains("greet") })
    }

    /** Rejects a caret outside of a class without mutating PSI. */
    fun testDiscover_outsideClass_isNotApplicable() {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("outside")?.getFileObject("file.kt") ?: return
        val ktFile: KtFile = session.getKtFileForPath(file.path) ?: return

        val result = KaExtractSuperComputer(ktFile, 0).discover()

        assertSame(KaExtractSuperComputer.Discovery.NotApplicable, result)
    }

    /** Verifies that the copied K2 Pull Up path moves each selected function into the interface. */
    fun testApply_realSession_extractInterfaceMovesSelectedMembers() {
        val fixture = createExtractInterfaceFixture() ?: return
        try {
            val result = fixture.apply()

            assertTrue("Expected Extract Interface to succeed, got $result", result is KaExtractSuperComputer.Apply.Success)
            result as KaExtractSuperComputer.Apply.Success
            assertTrue("Expected extracted interface, got:\n${result.targetText}", result.targetText.contains("interface IGreeter"))
            assertTrue("Expected greet in extracted interface, got:\n${result.targetText}", result.targetText.contains("fun greet(): String"))
            assertTrue("Expected farewell in extracted interface, got:\n${result.targetText}", result.targetText.contains("fun farewell(): String"))
            assertTrue("Expected source implementation to remain, got:\n${result.sourceText}", result.sourceText.contains("fun greet(): String = \"hello\""))
            assertTrue("Expected source implementation to remain, got:\n${result.sourceText}", result.sourceText.contains("fun farewell(): String = \"bye\""))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that the final target text persisted by Extract Interface contains separate,
     * syntactically valid declarations for every selected function.
     */
    fun testApply_realSession_formattedTargetHasSeparateValidFunctions() {
        val fixture = createExtractInterfaceFixture() ?: return
        try {
            val result = fixture.apply(abstractMembers = false)

            assertTrue("Expected Extract Interface to succeed, got $result", result is KaExtractSuperComputer.Apply.Success)
            result as KaExtractSuperComputer.Apply.Success
            assertTrue(
                "Expected generated functions to have a physical separator before formatting, got:\n${result.targetText}",
                Regex("fun greet\\(\\): String = \\\"hello\\\"\\R\\s*fun farewell\\(\\): String = \\\"bye\\\"")
                    .containsMatchIn(result.targetText),
            )
            val formattedTarget = formatExtractSuperText(result.targetText, "IGreeter.kt", project)
            val parsedTarget = KotlinFormatterUtils.createPsiFactory(project).createFile("IGreeter.kt", formattedTarget)
            val errors = PsiTreeUtil.collectElementsOfType(parsedTarget, PsiErrorElement::class.java)
            assertTrue(
                "Expected syntactically valid generated interface, got errors ${errors.map(PsiErrorElement::getErrorDescription)} in:\n$formattedTarget",
                errors.isEmpty(),
            )
            val functions = parsedTarget.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtClass>()
                .single { it.name == "IGreeter" }
                .declarations
                .filterIsInstance<KtNamedFunction>()
            assertEquals("Expected each selected function to be a separate interface member", listOf("greet", "farewell"), functions.map(KtNamedFunction::getName))
            assertEquals("Expected greet signature", "fun greet(): String = \"hello\"", functions[0].text)
            assertEquals("Expected farewell signature", "fun farewell(): String = \"bye\"", functions[1].text)
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Verifies that an extracted type in the source package is not rendered with a redundant FQ name. */
    fun testApply_realSession_samePackageUsesUnqualifiedSupertype() {
        val fixture = createExtractInterfaceFixture() ?: return
        try {
            val result = fixture.apply()

            assertTrue("Expected Extract Interface to succeed, got $result", result is KaExtractSuperComputer.Apply.Success)
            result as KaExtractSuperComputer.Apply.Success
            assertTrue(
                "Expected unqualified same-package supertype, got:\n${result.sourceText}",
                Regex("class\\s+Greeter\\s*:\\s*IGreeter").containsMatchIn(result.sourceText),
            )
            assertFalse("Did not expect same-package FQ supertype, got:\n${result.sourceText}", result.sourceText.contains("extractsuper.same.IGreeter"))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Creates a real standalone K2 source/target pair for Extract Interface integration tests. */
    private fun createExtractInterfaceFixture(): ExtractInterfaceFixture? {
        val stdlib = findKotlinStdlib() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Extract Super integration test")
            return null
        }
        val directory = Files.createTempDirectory("nbkotlin-extract-super")
        val sourcePath = directory.resolve("Greeter.kt")
        val targetPath = directory.resolve("IGreeter.kt")
        Files.writeString(
            sourcePath,
            """
            package extractsuper.same

            class Greeter {
                fun greet(): String = "hello"
                fun farewell(): String = "bye"
            }
            """.trimIndent(),
        )
        Files.writeString(targetPath, "package extractsuper.same\n\n")

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "extract-super-integration",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(directory),
        )
        val sourceFile = session.getKtFileForPath(sourcePath.toString())
            ?: error("Failed to obtain source KtFile")
        val targetFile = session.getKtFileForPath(targetPath.toString())
            ?: error("Failed to obtain target KtFile")
        return ExtractInterfaceFixture(directory, sourceFile, targetFile)
    }

    /** Locates the Kotlin standard-library JAR used by standalone analysis fixtures. */
    private fun findKotlinStdlib(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Holds one isolated source and destination PSI pair for a real K2 Extract Interface operation. */
    private data class ExtractInterfaceFixture(
        /** Temporary root containing both Kotlin files. */
        val directory: Path,
        /** Source class PSI supplied to the copied IDEA engine. */
        val sourceFile: KtFile,
        /** Already-created destination file PSI supplied to the copied IDEA engine. */
        val targetFile: KtFile,
    ) {
        /**
         * Applies Extract Interface with both source functions selected.
         *
         * @param abstractMembers whether selected members should be abstract in the generated interface.
         * @return outcome from the copied IDEA K2 engine.
         */
        fun apply(abstractMembers: Boolean = true): KaExtractSuperComputer.Apply {
            val sourceClass = sourceFile.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtClass>()
                .single { it.name == "Greeter" }
            val selectedOffsets = sourceClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                .filter { it.name == "greet" || it.name == "farewell" }
                .mapTo(linkedSetOf()) { it.textOffset }
            return KaExtractSuperComputer(sourceFile, sourceClass.textOffset).apply(
                ExtractSuperRequest(
                    classOffset = sourceClass.textOffset,
                    name = "IGreeter",
                    kind = ExtractSuperKind.INTERFACE,
                    selectedOffsets = selectedOffsets,
                    abstractOffsets = if (abstractMembers) selectedOffsets else emptySet(),
                    targetFileName = "IGreeter.kt",
                    targetPackage = "extractsuper.same",
                ),
                targetFile,
            )
        }
    }
}
