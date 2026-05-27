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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.completion

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase
import utils.getDocumentForFileObject

/**
 * Integration tests for [KtDocumentationRenderer].
 *
 * Uses a real [KotlinAnalysisAPISession] and the test project to verify that [KtDocumentationRenderer.buildHtml]
 * produces correct HTML for documented and undocumented Kotlin symbols.
 */
class KtDocumentationRendererTest : KotlinTestCase("KtDocumentationRenderer test", "completion") {

    private val fileName = "checkDocumentation.kt"

    private fun fileText(): String {
        val fo = dir.getFileObject(fileName)!!
        return getDocumentForFileObject(fo).getText(0, getDocumentForFileObject(fo).length)
    }

    private fun offsetOf(identifier: String): Int {
        val text = fileText()
        val idx = text.lastIndexOf(identifier)
        assertTrue("'$identifier' must be present in $fileName", idx >= 0)
        return idx
    }

    private fun buildHtmlAt(identifier: String): String? {
        val fo = dir.getFileObject(fileName)!!
        val absolutePath = FileUtil.toFile(fo)!!.absolutePath
        val doc = getDocumentForFileObject(fo)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.updateFileContent(absolutePath, doc.getText(0, doc.length))
        val offset = offsetOf(identifier)
        return KtDocumentationRenderer.buildHtml(project, fo, offset)
    }

    /**
     * Runs completion at the `functionWithKDoc(42)` call site and returns the items (each carrying
     * a [KaCompletionItem.symbolPointer]) matching prefix `"functionWith"`.
     */
    private fun functionItems(): List<KaCompletionItem> {
        val fo = dir.getFileObject(fileName)!!
        val absolutePath = FileUtil.toFile(fo)!!.absolutePath
        val doc = getDocumentForFileObject(fo)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.updateFileContent(absolutePath, doc.getText(0, doc.length))
        val ktFile = session.getKtFileForPath(absolutePath)!!
        val caret = doc.getText(0, doc.length).indexOf("functionWithKDoc(42)")
        assertTrue("call site must be present in $fileName", caret >= 0)
        return KaCompletionProvider.getItemsAt(ktFile, caret, prefix = "functionWith")
    }

    /**
     * [KtDocumentationRenderer.buildHtmlForPointer] renders the exact declaration the pointer stands
     * for: the documented and undocumented overloads yield *different* HTML. This is the regression
     * guard for the bug where the doc popup always showed the first list item regardless of which
     * candidate was selected.
     */
    fun testBuildHtmlForPointer_followsSelectedCandidate() {
        val items = functionItems()
        val documented = items.find { it.name == "functionWithKDoc" }
        val undocumented = items.find { it.name == "functionWithoutKDoc" }
        assertNotNull("functionWithKDoc must be among completion items", documented)
        assertNotNull("functionWithoutKDoc must be among completion items", undocumented)
        assertNotNull("completion items must carry a symbol pointer", documented!!.symbolPointer)
        assertNotNull("completion items must carry a symbol pointer", undocumented!!.symbolPointer)

        val fo = dir.getFileObject(fileName)!!
        val docHtml = KtDocumentationRenderer.buildHtmlForPointer(project, fo, documented.symbolPointer!!)
        val undocHtml = KtDocumentationRenderer.buildHtmlForPointer(project, fo, undocumented.symbolPointer!!)

        assertNotNull("documented pointer must render HTML", docHtml)
        assertNotNull("undocumented pointer must render HTML", undocHtml)
        assertTrue("documented HTML must name functionWithKDoc", docHtml!!.contains("functionWithKDoc"))
        assertTrue("documented HTML must include its KDoc", docHtml.contains("hover tests"))
        assertTrue("undocumented HTML must name functionWithoutKDoc", undocHtml!!.contains("functionWithoutKDoc"))
        assertFalse(
            "undocumented candidate must not inherit the documented function's KDoc",
            undocHtml.contains("hover tests")
        )
    }

    /**
     * For a reference to a documented function, [KtDocumentationRenderer.buildHtml] returns HTML
     * that contains the `fun` keyword and the function name.
     */
    fun testBuildHtml_functionWithKDoc_containsSignature() {
        val html = buildHtmlAt("functionWithKDoc")
        assertNotNull("buildHtml must return non-null for a documented function reference", html)
        assertTrue("HTML must contain 'fun'", html!!.contains("fun"))
        assertTrue("HTML must contain the function name", html.contains("functionWithKDoc"))
    }

    /**
     * For a reference to a documented function, the HTML includes the KDoc description text.
     */
    fun testBuildHtml_functionWithKDoc_containsKDoc() {
        val html = buildHtmlAt("functionWithKDoc")
        assertNotNull(html)
        assertTrue(
            "HTML must contain KDoc description text",
            html!!.contains("hover tests")
        )
    }

    /**
     * For a reference to an undocumented function, [KtDocumentationRenderer.buildHtml] returns
     * HTML with the signature but no `<hr>` KDoc divider.
     */
    fun testBuildHtml_unfunctionWithKDoc_noKDocSection() {
        val html = buildHtmlAt("functionWithoutKDoc")
        assertNotNull("buildHtml must return non-null for an undocumented function reference", html)
        assertFalse("HTML must not contain KDoc divider for undocumented symbol", html!!.contains("<hr"))
    }

    /**
     * [KtDocumentationRenderer.buildHtml] returns `null` when the offset points to whitespace
     * with no reference expression.
     */
    fun testBuildHtml_whitespaceOffset_returnsNull() {
        val fo = dir.getFileObject(fileName)!!
        val absolutePath = FileUtil.toFile(fo)!!.absolutePath
        val doc = getDocumentForFileObject(fo)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.updateFileContent(absolutePath, doc.getText(0, doc.length))
        val html = KtDocumentationRenderer.buildHtml(project, fo, 0)
        assertNull("buildHtml must return null at offset 0 (package keyword)", html)
    }
}
