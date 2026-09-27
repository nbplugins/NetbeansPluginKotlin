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
import io.github.nbplugins.kotlin.refactoring.KaInlineTypeAliasComputer
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Tests IDEA K2 strategy preparation and application for Kotlin Inline Type Alias. */
class KaInlineTypeAliasTest : KotlinTestCase("KaInlineTypeAliasTest", "inlineTypeAlias") {

    private companion object {
        const val CARET = "<caret>"
    }

    /** Finds the Kotlin standard-library JAR required by an isolated standalone K2 session. */
    private fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Builds a real K2 session backed by one fixture and returns its computer/file/temp directory. */
    private fun prepare(fixture: String): Triple<KaInlineTypeAliasComputer, KtFile, Path>? {
        val stdlib = findKotlinStdlib() ?: return null
        val fixtureFolder = dir.getFileObject(fixture) ?: error("Missing fixture: $fixture")
        val source = fixtureFolder.getFileObject("file.kt")?.asText() ?: error("Missing source for $fixture")
        val marker = source.indexOf(CARET)
        check(marker >= 0) { "Missing caret marker in $fixture" }
        val cleanSource = source.replace(CARET, "")
        val tempDirectory = Files.createTempDirectory("nbkotlin-inline-type-alias-$fixture")
        val tempFile = tempDirectory.resolve("file.kt")
        Files.writeString(tempFile, cleanSource)
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "inline-type-alias-$fixture",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tempDirectory),
        )
        val ktFile = session.getKtFileForPath(tempFile.toString()) ?: error("Missing KtFile for $tempFile")
        val files = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
        return Triple(KaInlineTypeAliasComputer(ktFile, marker, files), ktFile, tempDirectory)
    }

    /** A declaration caret produces a ready plan with its Kotlin usage. */
    fun testDeclarationCaret_collectsUsage() {
        val (computer, _, temporaryDirectory) = prepare("simple") ?: return
        try {
            val outcome = computer.compute()
            assertTrue("Expected ready result, got $outcome", outcome is KaInlineTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaInlineTypeAliasComputer.Outcome.Ready).result
            assertEquals("Name", result.declarationName)
            assertEquals(1, result.usages.values.sumOf { it.size })
            assertTrue("Declaration invocation must not retain a usage trigger", result.triggerUsage == null)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** A usage caret resolves back to the same alias and records the exact trigger reference. */
    fun testUsageCaret_resolvesAliasAndRecordsTrigger() {
        val (computer, _, temporaryDirectory) = prepare("caretOnUsage") ?: return
        try {
            val outcome = computer.compute()
            assertTrue("Expected ready result, got $outcome", outcome is KaInlineTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaInlineTypeAliasComputer.Outcome.Ready).result
            assertEquals("Name", result.declarationName)
            assertTrue("Usage invocation must retain its trigger", result.triggerUsage != null)
            assertEquals("Name", result.triggerUsage?.text)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** IDEA's real type-alias replacement strategy expands a simple alias and allows declaration deletion. */
    fun testApply_simpleAlias_expandsUsageAndDeletesDeclaration() {
        val (computer, ktFile, temporaryDirectory) = prepare("simple") ?: return
        try {
            val ready = computer.compute() as? KaInlineTypeAliasComputer.Outcome.Ready
            assertTrue("Expected ready alias plan, got $ready", ready != null)
            val result = ready!!.result
            result.usages.values.flatten().forEach { usage -> result.strategy.createReplacer(usage)?.invoke() }
            result.typeAlias.delete()

            assertFalse("Alias declaration must be deleted: ${ktFile.text}", ktFile.text.contains("typealias Name"))
            assertTrue("Usage must expand to String: ${ktFile.text}", ktFile.text.contains("name: String"))
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** IDEA's strategy substitutes the concrete arguments of each generic alias use independently. */
    fun testApply_genericAlias_preservesPerUsageSubstitutions() {
        val (computer, ktFile, temporaryDirectory) = prepare("generic") ?: return
        try {
            val ready = computer.compute() as? KaInlineTypeAliasComputer.Outcome.Ready
            assertNotNull("Expected ready generic alias plan", ready)
            val result = ready!!.result
            result.usages.values.flatten().forEach { usage -> result.strategy.createReplacer(usage)?.invoke() }
            result.typeAlias.delete()

            assertTrue(
                "String use must stay substituted: ${ktFile.text}",
                ktFile.text.contains("List<String>") || ktFile.text.contains("List<kotlin.String>"),
            )
            assertTrue(
                "Int use must stay substituted: ${ktFile.text}",
                ktFile.text.contains("List<Int>") || ktFile.text.contains("List<kotlin.Int>"),
            )
            assertFalse("Alias declaration must be deleted: ${ktFile.text}", ktFile.text.contains("typealias Names"))
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** The upstream processor must link so a future full IDEA processor route remains available. */
    fun testIdeaInlineTypeAliasProcessor_isLinkable() {
        try {
            Class.forName(
                "org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlineTypeAliasProcessor",
                true,
                javaClass.classLoader,
            )
        } catch (error: NoClassDefFoundError) {
            fail("KotlinInlineTypeAliasProcessor cannot link: ${error.message}")
        } catch (error: ClassNotFoundException) {
            fail("KotlinInlineTypeAliasProcessor is missing: ${error.message}")
        }
    }
}
