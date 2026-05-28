/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.completion

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase
import utils.getCaret
import utils.getDocumentForFileObject

/**
 * Unit tests for [KaParameterInfoProvider].
 *
 * Each test loads a Kotlin source file from the completion test-resources directory,
 * locates the `<caret>` marker in the document to obtain the analysis offset, and
 * verifies that [KaParameterInfoProvider.getParameterInfo] returns the expected
 * [org.netbeans.modules.csl.api.ParameterInfo].
 */
class KaParameterInfoProviderTest : KotlinTestCase("KaParameterInfoProvider test", "completion") {

    /**
     * Verifies that when the caret is inside the first argument, [ParameterInfo.getCurrentIndex]
     * returns 0 and the names list is non-empty.
     */
    fun testParameterInfo_insideFirstArg() {
        val fo = dir.getFileObject("paramInfoArg1.kt")
        assertNotNull("paramInfoArg1.kt must exist", fo)
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        assertTrue("caret marker must be present", caretOffset >= 0)

        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)
        assertNotNull("K2 session must contain paramInfoArg1.kt", ktFile)

        val info = KaParameterInfoProvider.getParameterInfo(ktFile!!, caretOffset)
        assertNotNull("ParameterInfo must not be null", info)
        assertTrue("names list must be non-empty", info.names.isNotEmpty())
        assertEquals("current index must be 0 for first arg", 0, info.currentIndex)
    }

    /**
     * Verifies that when the caret is inside the second argument (after the first comma),
     * [ParameterInfo.getCurrentIndex] returns 1.
     */
    fun testParameterInfo_insideSecondArg() {
        val fo = dir.getFileObject("paramInfoArg2.kt")
        assertNotNull("paramInfoArg2.kt must exist", fo)
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        assertTrue("caret marker must be present", caretOffset >= 0)

        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)
        assertNotNull("K2 session must contain paramInfoArg2.kt", ktFile)

        val info = KaParameterInfoProvider.getParameterInfo(ktFile!!, caretOffset)
        assertNotNull("ParameterInfo must not be null", info)
        assertTrue("names list must be non-empty", info.names.isNotEmpty())
        assertEquals("current index must be 1 for second arg", 1, info.currentIndex)
    }

    /**
     * Verifies that when the caret is at offset 0 (outside any call), [ParameterInfo.NONE]
     * is returned.
     */
    fun testParameterInfo_outsideCall() {
        val fo = dir.getFileObject("paramInfoArg1.kt")
        assertNotNull("paramInfoArg1.kt must exist", fo)

        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)
        assertNotNull("K2 session must contain paramInfoArg1.kt", ktFile)

        val info = KaParameterInfoProvider.getParameterInfo(ktFile!!, 0)
        // At offset 0 (start of file) there is no enclosing call — must return NONE
        assertTrue(
            "ParameterInfo at offset 0 must be NONE (names empty or index -1)",
            info.names.isNullOrEmpty() || info.currentIndex == -1
        )
    }

    /**
     * Verifies that a vararg parameter is rendered with the "vararg " prefix in the names list.
     */
    fun testParameterInfo_varargParam() {
        val fo = dir.getFileObject("paramInfoArg1.kt")
        assertNotNull("paramInfoArg1.kt must exist", fo)
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)

        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val info = KaParameterInfoProvider.getParameterInfo(ktFile, caretOffset)
        assertTrue("names list must be non-empty", info.names.isNotEmpty())
        val hasVararg = info.names.any { it.startsWith("vararg ") }
        assertTrue("at least one parameter must be rendered with 'vararg ' prefix", hasVararg)
    }

    /**
     * Verifies that a parameter with a default value is rendered with the " = ..." suffix.
     */
    fun testParameterInfo_defaultValue() {
        val fo = dir.getFileObject("paramInfoArg1.kt")
        assertNotNull("paramInfoArg1.kt must exist", fo)
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)

        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val info = KaParameterInfoProvider.getParameterInfo(ktFile, caretOffset)
        assertTrue("names list must be non-empty", info.names.isNotEmpty())
        val hasDefault = info.names.any { it.endsWith(" = ...") }
        assertTrue("at least one parameter must be rendered with ' = ...' suffix", hasDefault)
    }
}
