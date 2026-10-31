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

import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.nodes.Node
import utils.KotlinTestCase

/**
 * Tests the target resolution of [ConvertToKotlinAction].
 *
 * The action is shown in the Refactor menu, which NetBeans renders from the activated nodes; the
 * tests exercise that resolution over the `projForTest/src/j2k` fixtures. The conversion itself is
 * covered by [Java2KotlinConverterTest].
 */
class ConvertToKotlinActionTest : KotlinTestCase("ConvertToKotlinActionTest", "j2k") {

    /** Action under test. */
    private val action = ConvertToKotlinAction()

    /**
     * Returns the node of a fixture file.
     *
     * @param name name relative to the fixture directory, e.g. `simple.java`
     * @return the file's node delegate
     */
    private fun nodeOf(name: String): Node {
        val fileObject = dir.getFileObject(name)
        assertNotNull("fixture $name", fileObject)
        return DataObject.find(fileObject).nodeDelegate
    }

    /** A single Java source file is the accepted selection. */
    fun testJavaFileNodeIsAccepted() {
        val file = action.javaFileAmong(arrayOf(nodeOf("simple.java")))

        assertNotNull("simple.java must be accepted", file)
        assertEquals("simple", file?.name)
    }

    /** Kotlin files cannot be converted. */
    fun testKotlinFileNodeIsRejected() {
        val fileObject = FileUtil.createData(dir, "actionProbe.kt")

        assertEquals(
            "a Kotlin file must not be accepted",
            null,
            action.javaFileAmong(arrayOf(DataObject.find(fileObject).nodeDelegate)),
        )
    }

    /** Nothing selected means nothing to convert. */
    fun testEmptySelectionIsRejected() {
        assertEquals(null, action.javaFileAmong(emptyArray()))
    }

    /** Conversion works on one file at a time. */
    fun testMultiSelectionIsRejected() {
        val nodes = arrayOf(nodeOf("simple.java"), nodeOf("mixed.java"))

        assertEquals(null, action.javaFileAmong(nodes))
    }

    /** [ConvertToKotlinAction.enable] follows the same resolution as the action itself. */
    fun testEnableFollowsSelection() {
        assertTrue("a Java file enables the action", action.enable(arrayOf(nodeOf("simple.java"))))
        assertFalse("no selection disables the action", action.enable(emptyArray()))
    }

    /** With no usable node the action falls back to the (empty) global context. */
    fun testTargetFileFallsBackToGlobalContext() {
        assertEquals(null, action.targetFile(emptyArray()))
    }
}
