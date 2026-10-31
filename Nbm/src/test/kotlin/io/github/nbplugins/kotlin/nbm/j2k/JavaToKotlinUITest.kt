/*
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
 */
package io.github.nbplugins.kotlin.nbm.j2k

import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.util.HelpCtx

/**
 * Unit tests for the Convert to Kotlin Refactoring UI adapter.
 *
 * The adapter has no parameters panel by design: that is what makes NetBeans open the Refactoring
 * window directly instead of an empty parameters dialog.
 */
class JavaToKotlinUITest : NbTestCase("JavaToKotlinUITest") {

    private lateinit var root: FileObject

    override fun setUp() {
        super.setUp()
        root = FileUtil.createMemoryFileSystem().root
    }

    /**
     * Builds the UI adapter for a Java file, along with its carrier.
     *
     * @param fileName name of the Java file to create, or to reuse when the test creates it twice
     */
    private fun uiFor(fileName: String = "Source.java"): Pair<JavaToKotlinUI, JavaToKotlinRefactoring> {
        val java = root.getFileObject(fileName) ?: root.createData(fileName)
        val refactoring = JavaToKotlinRefactoring(projectStub(), java)
        return JavaToKotlinUI(refactoring) to refactoring
    }

    /** The window is titled like the action, so both entry points read the same. */
    fun testName() {
        assertEquals("Convert to Kotlin", uiFor().first.name)
        assertEquals(
            "the action and the Refactoring window must show the same name",
            JavaToKotlinUI.NAME,
            ConvertToKotlinAction().name,
        )
    }

    /** The description names the converted file; it is used as the window tooltip. */
    fun testDescriptionNamesTheFile() {
        assertTrue(
            "the description must mention the converted file",
            uiFor().first.description.contains("Source.java"),
        )
    }

    /** The conversion changes code, so it is not a query. */
    fun testIsNotQuery() {
        assertFalse(uiFor().first.isQuery)
    }

    /** No parameters panel: NetBeans opens the Refactoring window directly. */
    fun testHasNoParameters() {
        val ui = uiFor().first

        assertFalse(ui.hasParameters())
        assertNull("no parameters panel may be returned", ui.getPanel(null))
        assertNull("nothing to transfer into the refactoring", ui.setParameters())
        assertNull("no parameters to check", ui.checkParameters())
    }

    /** The adapter exposes the carrier the refactoring framework has to run. */
    fun testReturnsTheCarrier() {
        val (ui, refactoring) = uiFor()

        assertSame(refactoring, ui.refactoring)
    }

    /** No dedicated help page exists for this refactoring. */
    fun testHelpCtx() {
        assertEquals(HelpCtx.DEFAULT_HELP, uiFor().first.helpCtx)
    }

    /**
     * Returns a project placeholder: the tested UI paths never dereference it.
     *
     * @return a project stub that fails when something actually uses it
     */
    private fun projectStub(): org.netbeans.api.project.Project =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(org.netbeans.api.project.Project::class.java),
        ) { _, _, _ ->
            throw UnsupportedOperationException("project must not be used by the UI adapter")
        } as org.netbeans.api.project.Project
}
