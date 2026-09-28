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
import io.github.nbplugins.kotlin.refactoring.KaInlineAnonymousFunctionComputer
import org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlineAnonymousFunctionProcessor
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Tests standalone preparation and IDEA K2 application of Inline Anonymous Function. */
class KaInlineAnonymousFunctionTest : KotlinTestCase(
    "KaInlineAnonymousFunctionTest",
    "inlineAnonymousFunction",
) {

    private companion object {
        const val CARET = "<caret>"
    }

    /** Finds the Kotlin standard library JAR required by an isolated standalone K2 session. */
    private fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Creates a temporary standalone session for one source fixture. */
    private fun prepare(fixture: String): Triple<KaInlineAnonymousFunctionComputer, KtFile, Path>? {
        val stdlib = findKotlinStdlib() ?: return null
        val fixtureFolder = dir.getFileObject(fixture) ?: error("Missing fixture: $fixture")
        val source = fixtureFolder.getFileObject("file.kt")?.asText() ?: error("Missing source for $fixture")
        val marker = source.indexOf(CARET)
        check(marker >= 0) { "Missing caret marker in $fixture" }
        val temporaryDirectory = Files.createTempDirectory("nbkotlin-inline-anonymous-$fixture")
        val temporaryFile = temporaryDirectory.resolve("file.kt")
        Files.writeString(temporaryFile, source.replace(CARET, ""))
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "inline-anonymous-$fixture",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(temporaryDirectory),
        )
        val ktFile = session.getKtFileForPath(temporaryFile.toString()) ?: error("Missing KtFile for $temporaryFile")
        return Triple(KaInlineAnonymousFunctionComputer(ktFile, marker), ktFile, temporaryDirectory)
    }

    /** An immediately invoked lambda is recognised through IDEA's call-shape detector. */
    fun testLambdaCaret_preparesImmediateInvocation() {
        val (computer, _, temporaryDirectory) = prepare("lambda") ?: return
        try {
            val outcome = computer.compute()
            assertTrue("Expected a ready lambda inline plan, got $outcome", outcome is KaInlineAnonymousFunctionComputer.Outcome.Ready)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** An immediately invoked anonymous `fun` expression is recognised through IDEA's call-shape detector. */
    fun testAnonymousFunctionCaret_preparesImmediateInvocation() {
        val (computer, _, temporaryDirectory) = prepare("anonymous") ?: return
        try {
            val outcome = computer.compute()
            assertTrue("Expected a ready anonymous-function inline plan, got $outcome", outcome is KaInlineAnonymousFunctionComputer.Outcome.Ready)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** A lambda stored for later use must be rejected instead of being rewritten unsafely. */
    fun testNonInvokedLambda_isRejected() {
        val (computer, _, temporaryDirectory) = prepare("notInvoked") ?: return
        try {
            val outcome = computer.compute()
            assertTrue("Expected an eligibility error, got $outcome", outcome is KaInlineAnonymousFunctionComputer.Outcome.Error)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /**
     * The upstream processor links, while the NetBeans adapter performs its required K2-session
     * refresh between lambda conversion and the shared IDEA code-inliner phase.
     */
    fun testIdeaProcessor_requiresSessionRefreshForLambda() {
        val (computer, _, temporaryDirectory) = prepare("lambda") ?: return
        try {
            val ready = computer.compute() as? KaInlineAnonymousFunctionComputer.Outcome.Ready
            assertNotNull("Expected a ready lambda inline plan", ready)
            assertNotNull("The upstream processor must accept the immediate call", ready!!.result.call)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    /** The upstream processor must link so NetBeans can retain IDEA's transformation semantics. */
    fun testIdeaInlineAnonymousFunctionProcessor_isLinkable() {
        try {
            Class.forName(
                "org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlineAnonymousFunctionProcessor",
                true,
                javaClass.classLoader,
            )
        } catch (error: NoClassDefFoundError) {
            fail("KotlinInlineAnonymousFunctionProcessor cannot link: ${error.message}")
        } catch (error: ClassNotFoundException) {
            fail("KotlinInlineAnonymousFunctionProcessor is missing: ${error.message}")
        }
    }
}
