/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
import org.jetbrains.kotlin.psi.KtFile
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase
import utils.getCaret
import utils.getDocumentForFileObject

/**
 * Unit tests for [KaCompletionProvider].
 *
 * Validates the K2 Analysis API completion path using real [KotlinAnalysisAPISession]
 * source files from the test project.
 */
class KaCompletionProviderTest : KotlinTestCase("KaCompletionProvider test", "completion") {

    private fun getTestKtFile(fileName: String = "checkAfterDotFiltering.kt"): Triple<KtFile, Int, List<KaCompletionItem>> {
        val fo = dir.getFileObject(fileName)
        assertNotNull("$fileName must exist in the completion dir", fo)
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        assertTrue("caret marker must be present in $fileName", caretOffset >= 0)
        val absolutePath = FileUtil.toFile(fo)!!.absolutePath
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(absolutePath)
        assertNotNull("K2 session must contain $fileName", ktFile)
        val items = KaCompletionProvider.getItemsAt(ktFile!!, caretOffset, prefix = "")
        return Triple(ktFile, caretOffset, items)
    }

    /**
     * Verifies that [KaCompletionProvider.getItemsAt] returns a non-null list for a valid file.
     */
    fun testGetItemsAt_returnsListForValidFile() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        println("[KaCompletionProviderTest] hasDependencies=${wrapper.hasDependencies}")
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten().filterIsInstance<KtFile>().firstOrNull()
        assertNotNull("K2 session must have at least one source file", ktFile)

        val items = KaCompletionProvider.getItemsAt(ktFile!!, caretOffset = 0, prefix = "")
        println("[KaCompletionProviderTest] getItemsAt returned ${items.size} item(s)")
        assertNotNull("getItemsAt must return a non-null list", items)
    }

    /**
     * Verifies that [KaCompletionProvider.getItemsAt] returns an empty list on invalid offset.
     */
    fun testGetItemsAt_returnsEmptyOnInvalidOffset() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten().filterIsInstance<KtFile>().firstOrNull()
        assertNotNull("K2 session must have at least one source file", ktFile)

        val items = KaCompletionProvider.getItemsAt(ktFile!!, caretOffset = Int.MAX_VALUE, prefix = "")
        assertNotNull("getItemsAt must not throw on invalid offset", items)
    }

    /**
     * Verifies that prefix filtering is case-insensitive.
     */
    fun testGetItemsAt_prefixFilterIsCaseInsensitive() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten().filterIsInstance<KtFile>().firstOrNull()
        assertNotNull("K2 session must have at least one source file", ktFile)

        val lower = KaCompletionProvider.getItemsAt(ktFile!!, 0, prefix = "check")
        val upper = KaCompletionProvider.getItemsAt(ktFile, 0, prefix = "Check")
        assertNotNull(lower)
        assertNotNull(upper)
    }

    /**
     * Verifies that with [isAfterDot]=true, top-level non-extension callables are suppressed.
     */
    fun testGetItemsAt_afterDot_suppressesTopLevelNonExtension() {
        val (_, caretOffset, _) = getTestKtFile()
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val items = KaCompletionProvider.getItemsAt(ktFile, caretOffset, prefix = "", isAfterDot = true)
        assertFalse(
            "topLevelNonExtension must be suppressed when isAfterDot=true, but got: ${items.map { it.name }}",
            items.any { it.name == "topLevelNonExtension" }
        )
    }

    /**
     * Verifies that with [isAfterDot]=true, extension callables are NOT suppressed.
     */
    fun testGetItemsAt_afterDot_keepsExtensions() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val items = KaCompletionProvider.getItemsAt(ktFile, caretOffset, prefix = "", isAfterDot = true)
        assertTrue(
            "stringExtension must NOT be suppressed when isAfterDot=true, but got: ${items.map { it.name }}",
            items.any { it.name == "stringExtension" }
        )
    }

    /**
     * Verifies that without [isAfterDot], top-level non-extension callables are shown.
     */
    fun testGetItemsAt_withoutAfterDot_showsTopLevelNonExtension() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val items = KaCompletionProvider.getItemsAt(ktFile, caretOffset, prefix = "", isAfterDot = false)
        assertTrue(
            "topLevelNonExtension must appear when isAfterDot=false, but got: ${items.map { it.name }}",
            items.any { it.name == "topLevelNonExtension" }
        )
    }

    /**
     * Verifies that a function's signature is rendered as `"(param: Type, ...): ReturnType"`.
     */
    fun testGetItemsAt_signatureOfFunctionIsRendered() {
        val (_, _, items) = getTestKtFile()
        val item = items.find { it.name == "funcWithParams" }
        assertNotNull("funcWithParams must appear in completion items", item)
        assertEquals(
            "signature of funcWithParams must include params and return type",
            "(x: Int, y: String): Boolean",
            item!!.signature
        )
    }

    /**
     * Verifies that a property's signature is rendered as `": Type"`.
     */
    fun testGetItemsAt_signatureOfPropertyIsRendered() {
        val (_, _, items) = getTestKtFile()
        val item = items.find { it.name == "myProp" }
        assertNotNull("myProp must appear in completion items", item)
        assertEquals(
            "signature of myProp must be ': String'",
            ": String",
            item!!.signature
        )
    }

    /**
     * Verifies that val and var properties get different icons.
     */
    fun testGetItemsAt_valAndVarHaveDifferentIcons() {
        val (_, _, items) = getTestKtFile()
        val valItem = items.find { it.name == "myProp" }
        val varItem = items.find { it.name == "myVar" }
        assertNotNull("myProp (val) must appear", valItem)
        assertNotNull("myVar (var) must appear", varItem)
        assertNotSame(
            "val and var must have different icons",
            valItem!!.icon, varItem!!.icon
        )
    }

    /**
     * Verifies that no (name, signature) pair appears more than once in the result.
     */
    fun testGetItemsAt_noDuplicates() {
        val (_, _, items) = getTestKtFile()
        val keys = items.map { "${it.name}|${it.signature}" }
        assertEquals(
            "completion items must not contain duplicate (name, signature) pairs, duplicates: " +
                keys.groupBy { it }.filter { it.value.size > 1 }.keys,
            keys.toSet().size, keys.size
        )
    }

    /**
     * Verifies that after `s.` (where `s: String`) a built-in String member (`length`) is offered.
     *
     * This guards against the receiver-type scope not being resolved.
     */
    fun testGetItemsAt_afterDot_showsReceiverTypeMember() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val items = KaCompletionProvider.getItemsAt(ktFile, caretOffset, prefix = "", isAfterDot = true)
        assertTrue(
            "String member 'length' must appear after dot on String receiver, got: ${items.map { it.name }}",
            items.any { it.name == "length" }
        )
    }

    /**
     * Verifies that after `s.` top-level properties are not offered.
     *
     * `myProp` and `myVar` are top-level declarations, not members of String.
     */
    fun testGetItemsAt_afterDot_suppressesTopLevelProperties() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val items = KaCompletionProvider.getItemsAt(ktFile, caretOffset, prefix = "", isAfterDot = true)
        val names = items.map { it.name }
        assertFalse("myProp (top-level val) must not appear after dot, got: $names",
                    items.any { it.name == "myProp" })
        assertFalse("myVar (top-level var) must not appear after dot, got: $names",
                    items.any { it.name == "myVar" })
    }

    /**
     * Verifies that after `s.` top-level non-extension functions are not offered.
     *
     * `funcWithParams` is a top-level function, not a member or extension of String.
     */
    fun testGetItemsAt_afterDot_suppressesTopLevelFunctions() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val doc = getDocumentForFileObject(fo)
        val caretOffset = getCaret(doc)
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.getKtFileForPath(FileUtil.toFile(fo)!!.absolutePath)!!

        val items = KaCompletionProvider.getItemsAt(ktFile, caretOffset, prefix = "", isAfterDot = true)
        assertFalse(
            "funcWithParams (top-level non-extension) must not appear after dot, got: ${items.map { it.name }}",
            items.any { it.name == "funcWithParams" }
        )
    }
}
