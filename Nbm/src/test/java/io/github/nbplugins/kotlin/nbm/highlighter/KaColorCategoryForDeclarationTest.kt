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
package io.github.nbplugins.kotlin.nbm.highlighter

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase
import utils.getDocumentForFileObject

/**
 * Integration tests for [KaHighlightColorMapper.colorCategoryForDeclaration].
 *
 * Uses a real [KotlinAnalysisAPISession] to resolve symbols from the test project
 * and verifies that the correct color category name is returned for each declaration kind.
 */
class KaColorCategoryForDeclarationTest : KotlinTestCase("KaColorCategoryForDeclaration test", "completion") {

    private val fileName = "checkDocumentation.kt"

    /**
     * Resolves the symbol referenced at the last occurrence of [identifier] in the test file,
     * then calls [KaHighlightColorMapper.colorCategoryForDeclaration] on it inside an analyze block.
     */
    private fun categoryFor(identifier: String): String? {
        val fo = dir.getFileObject(fileName)!!
        val absolutePath = FileUtil.toFile(fo)!!.absolutePath
        val doc = getDocumentForFileObject(fo)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.updateFileContent(absolutePath, doc.getText(0, doc.length))
        val kaKtFile = session.getKtFileForPath(absolutePath) ?: return null
        val text = doc.getText(0, doc.length)
        val offset = text.lastIndexOf(identifier)
        if (offset < 0) return null
        val element = kaKtFile.findElementAt(offset) ?: return null
        val ref = PsiTreeUtil.getNonStrictParentOfType(element, KtReferenceExpression::class.java)
            ?: return null
        return analyze(kaKtFile) {
            val symbol = ref.mainReference?.resolveToSymbol() as? KaDeclarationSymbol
                ?: return@analyze null
            KaHighlightColorMapper.colorCategoryForDeclaration(symbol)
        }
    }

    /**
     * A reference to a top-level function resolves to `"KOTLIN_FUNCTION_DECLARATION"`.
     */
    fun testFunctionDeclaration_returnsCorrectCategory() {
        val category = categoryFor("functionWithKDoc")
        assertEquals("KOTLIN_FUNCTION_DECLARATION", category)
    }
}
