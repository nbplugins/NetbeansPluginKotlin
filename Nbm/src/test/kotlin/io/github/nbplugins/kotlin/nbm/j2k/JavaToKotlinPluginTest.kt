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
package io.github.nbplugins.kotlin.nbm.j2k

import org.jetbrains.kotlin.refactorings.rename.KotlinRefactoringsFactory
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProvider
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil

/**
 * Unit tests for the Convert to Kotlin refactoring carrier and plugin.
 *
 * These cover the parameter transport and the guard conditions that reject a conversion before
 * the J2K engine runs. The end-to-end conversion is verified by [Java2KotlinConverterTest] against
 * a real K2 session.
 */
class JavaToKotlinPluginTest : NbTestCase("JavaToKotlinPluginTest") {

    private lateinit var root: FileObject

    override fun setUp() {
        super.setUp()
        root = FileUtil.createMemoryFileSystem().root
    }

    /** The carrier exposes the Java file through its refactoring source and starts with no error. */
    fun testRefactoringCarrier_exposesJavaFile() {
        val java = root.createData("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)

        assertEquals(
            "the Java file must be the refactoring source",
            java,
            refactoring.refactoringSource.lookup(FileObject::class.java),
        )
        assertNull("no failure is recorded before the conversion runs", refactoring.failureMessage)
    }

    /** A failure message set by the element is readable by the caller after the session finishes. */
    fun testRefactoringCarrier_recordsFailureMessage() {
        val java = root.createData("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)

        refactoring.failureMessage = "boom"

        assertEquals("boom", refactoring.failureMessage)
    }

    /** A missing Kotlin sibling is accepted, so the conversion is allowed to register its element. */
    fun testValidate_acceptsConvertibleFile() {
        val java = root.createData("Source.java")

        assertNull(
            "a convertible file must not produce a problem",
            JavaToKotlinRefactoring(projectStub(), java).validate(),
        )
    }

    /** An existing sibling `.kt` is a fatal problem, so nothing is ever overwritten. */
    fun testValidate_rejectsExistingKotlinFile() {
        val java = root.createData("Source.java")
        root.createData("Source.kt")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)

        val problem = refactoring.validate()

        assertNotNull("an existing .kt must be rejected", problem)
        assertTrue("the problem must be fatal", (problem as Problem).isFatal)
    }

    /**
     * Undo recreates the deleted Java file as a new `FileObject`; redo must not reuse the stale one.
     *
     * `Undo Last Refactoring` runs `restoreDeletions`, which creates a new handle for the restored
     * file. The handle captured when the action ran points at a deleted file, and a second
     * `performChange` would fail its `isValid` check instead of converting again.
     */
    fun testCurrentJavaFile_resolvesRecreatedFile() {
        val java = root.createData("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)
        java.delete()
        val recreated = root.createData("Source.java")

        val current = refactoring.currentJavaFile()

        assertSame("the recreated file must be resolved", recreated, current)
        assertSame("the carrier must keep the new handle", recreated, refactoring.javaFile)
    }

    /** The restored file passes validation, so a redo is not rejected as a missing target. */
    fun testValidate_acceptsRecreatedFile() {
        val java = root.createData("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)
        java.delete()
        root.createData("Source.java")

        assertNull("the restored file must pass validation", refactoring.validate())
    }

    /** A target that stays deleted is reported as missing instead of being converted. */
    fun testCurrentJavaFile_reportsMissingFile() {
        val java = root.createData("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)
        java.delete()

        assertNull("a missing target must not be resolved", refactoring.currentJavaFile())
    }

    /** A deleted or non-file target is rejected instead of being converted. */
    fun testValidate_rejectsInvalidTarget() {
        val folder = root.createFolder("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), folder)

        val problem = refactoring.validate()

        assertNotNull("a folder must be rejected", problem)
        assertTrue("the problem must be fatal", (problem as Problem).isFatal)
    }

    /**
     * The target is validated in `preCheck`, because NetBeans silently drops a `prepare` problem
     * when the refactoring has no parameters panel.
     */
    fun testPreCheck_reportsTargetProblem() {
        val java = root.createData("Source.java")
        root.createData("Source.kt")
        val plugin = JavaToKotlinPlugin(JavaToKotlinRefactoring(projectStub(), java))

        val problem = plugin.preCheck()

        assertNotNull("preCheck must report the existing .kt", problem)
        assertTrue("the problem must be fatal", (problem as Problem).isFatal)
    }

    /** A convertible file passes `preCheck`, so the Refactoring window opens. */
    fun testPreCheck_acceptsConvertibleFile() {
        val java = root.createData("Source.java")
        val plugin = JavaToKotlinPlugin(JavaToKotlinRefactoring(projectStub(), java))

        assertNull("a convertible file must pass preCheck", plugin.preCheck())
    }

    /**
     * The plugin must not be a progress provider: NetBeans then shows the (empty) parameters
     * dialog instead of the Refactoring window for this parameterless refactoring.
     */
    fun testPlugin_isNotProgressProvider() {
        val java = root.createData("Source.java")
        val plugin = JavaToKotlinPlugin(JavaToKotlinRefactoring(projectStub(), java))

        assertFalse(
            "a progress-providing plugin makes NetBeans open a parameters dialog instead of the Refactoring window",
            plugin is ProgressProvider,
        )
    }

    /** The parameter hooks report no problems and the cancel hook performs no work. */
    fun testPlugin_lifecycleHooks() {
        val java = root.createData("Source.java")
        val plugin = JavaToKotlinPlugin(JavaToKotlinRefactoring(projectStub(), java))

        assertNull(plugin.fastCheckParameters())
        assertNull(plugin.checkParameters())
        plugin.cancelRequest()
    }

    /** The element points the undo entry at the Java file and has no editor position. */
    fun testElement_describesItsChange() {
        val java = root.createData("Source.java")
        val element = JavaToKotlinElement(JavaToKotlinRefactoring(projectStub(), java))

        assertEquals("Convert to Kotlin", element.text)
        assertEquals("Convert to Kotlin", element.displayText)
        assertEquals("the undo entry must point at the Java file", java, element.parentFile)
        assertEquals(java, element.lookup.lookup(FileObject::class.java))
        assertNull("the change spans two files, so it has no single position", element.position)
    }

    /** The shared plugin factory must dispatch this carrier before its Kotlin-document guard. */
    fun testFactory_createsPluginForJavaCarrier() {
        val java = root.createData("Source.java")
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)

        val plugin = KotlinRefactoringsFactory().createInstance(refactoring)

        assertTrue(
            "Convert to Kotlin must not be rejected by the editor-document applicability guard",
            plugin is JavaToKotlinPlugin,
        )
    }

    /**
     * A conversion that cannot reach the analysis session must leave no Kotlin file behind and
     * must keep the Java original, so a failure is never destructive.
     */
    fun testPerformChange_failureKeepsJavaAndRemovesKotlin() {
        val source = "package demo;\n\nclass Source {}\n"
        val java = root.createData("Source.java")
        java.getOutputStream().use { it.write(source.toByteArray(Charsets.UTF_8)) }
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)

        JavaToKotlinElement(refactoring).performChange()

        assertNotNull("the failure must be reported to the caller", refactoring.failureMessage)
        assertEquals("the Java original must be intact", source, java.asText())
        assertNull("no Kotlin file may survive a failed conversion", root.getFileObject("Source.kt"))
    }

    /**
     * Wraps [refactoring] in the plugin under test.
     *
     * @param refactoring carrier the guard conditions are checked against
     * @return the plugin instance
     */
    private fun plugin(refactoring: JavaToKotlinRefactoring): JavaToKotlinPlugin =
        JavaToKotlinPlugin(refactoring)

    /**
     * Returns a project placeholder: the tested code paths never dereference it.
     *
     * @return a project stub that fails when a conversion actually runs
     */
    private fun projectStub(): org.netbeans.api.project.Project =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(org.netbeans.api.project.Project::class.java),
        ) { _, _, _ ->
            throw UnsupportedOperationException("project must not be used by the guard paths")
        } as org.netbeans.api.project.Project
}
