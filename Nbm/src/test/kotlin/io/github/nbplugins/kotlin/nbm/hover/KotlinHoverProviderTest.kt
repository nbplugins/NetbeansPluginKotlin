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
package io.github.nbplugins.kotlin.nbm.hover

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase
import utils.getDocumentForFileObject

/**
 * Integration tests for [KotlinHoverProvider].
 *
 * Uses a real [KotlinAnalysisAPISession] and the test project to verify that
 * [KotlinHoverProvider.getHoverContent] returns correct HTML for documented and
 * undocumented Kotlin symbols, and `null` for non-symbol positions.
 */
class KotlinHoverProviderTest : KotlinTestCase("KotlinHoverProvider test", "completion") {

    private val provider = KotlinHoverProvider()
    private val fileName = "checkDocumentation.kt"

    private fun prepareDocument(): Pair<org.openide.filesystems.FileObject, javax.swing.text.Document> {
        val fo = dir.getFileObject(fileName)!!
        val absolutePath = FileUtil.toFile(fo)!!.absolutePath
        val doc = getDocumentForFileObject(fo)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.updateFileContent(absolutePath, doc.getText(0, doc.length))
        return fo to doc
    }

    private fun offsetOf(identifier: String): Int {
        val (_, doc) = prepareDocument()
        val text = doc.getText(0, doc.length)
        val idx = text.lastIndexOf(identifier)
        assertTrue("'$identifier' must be present in $fileName", idx >= 0)
        return idx
    }

    /**
     * [KotlinHoverProvider.getHoverContent] returns non-null HTML containing the function
     * signature for a reference to a documented function.
     */
    fun testGetHoverContent_documentedSymbol_returnsHtml() {
        val (_, doc) = prepareDocument()
        val offset = offsetOf("functionWithKDoc")
        val future = provider.getHoverContent(doc, offset)
        val html = future.get()
        assertNotNull("getHoverContent must return non-null for a documented function", html)
        assertTrue("HTML must contain 'fun'", html!!.contains("fun"))
        assertTrue("HTML must contain the function name", html.contains("functionWithKDoc"))
    }

    /**
     * [KotlinHoverProvider.getHoverContent] includes the KDoc text for a documented function.
     */
    fun testGetHoverContent_documentedSymbol_containsKDoc() {
        val (_, doc) = prepareDocument()
        val offset = offsetOf("functionWithKDoc")
        val html = provider.getHoverContent(doc, offset).get()
        assertNotNull(html)
        assertTrue("HTML must include the KDoc description", html!!.contains("hover tests"))
    }

    /**
     * [KotlinHoverProvider.getHoverContent] returns non-null HTML for an undocumented symbol
     * but without a KDoc section.
     */
    fun testGetHoverContent_undocumentedSymbol_noKDocSection() {
        val (_, doc) = prepareDocument()
        val offset = offsetOf("functionWithoutKDoc")
        val html = provider.getHoverContent(doc, offset).get()
        assertNotNull("getHoverContent must return non-null for an undocumented function", html)
        assertFalse("HTML must not contain KDoc divider for undocumented symbol", html!!.contains("<hr"))
    }

    /**
     * [KotlinHoverProvider.getHoverContent] returns `null` when the offset points to a
     * position with no resolvable symbol (offset 0 = package keyword).
     */
    fun testGetHoverContent_noSymbol_returnsNull() {
        val (_, doc) = prepareDocument()
        val html = provider.getHoverContent(doc, 0).get()
        assertNull("getHoverContent must return null at offset 0 (package keyword)", html)
    }
}
