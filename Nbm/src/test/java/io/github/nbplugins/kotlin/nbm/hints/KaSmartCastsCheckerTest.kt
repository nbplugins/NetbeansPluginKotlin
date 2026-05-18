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
package io.github.nbplugins.kotlin.nbm.hints

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import utils.KotlinTestCase
import utils.getCaret
import utils.getDocumentForFileObject

/**
 * Unit tests for [getSmartCastHoverK2].
 *
 * Uses the existing `smartCast` test fixture from the `semantic` resource directory.
 * Verifies that [getSmartCastHoverK2] returns null for a non-smart-cast expression.
 */
class KaSmartCastsCheckerTest : KotlinTestCase("KaSmartCastsChecker", "semantic") {

    private fun getKaKtFileOrSkip(path: String): KtFile? {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            println("KaSmartCastsCheckerTest: skipping — no K2 dependencies")
            return null
        }
        return wrapper.getKtFileForPath(path)
    }

    /**
     * A regular name expression that is not smart-cast should produce no hint.
     */
    fun testNoHintForNonSmartCast() {
        val file = dir.getFileObject("simpleClass.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val ktFile = KotlinPsiManager.getParsedFile(file)!!
        val parserResult = KotlinParserResult(null, ktFile, file, project, kaKtFile)

        // Find the first simple name expression in the file
        val expr = ktFile.findDescendantOfType<KtSimpleNameExpression>() ?: return

        val hint = getSmartCastHoverK2(expr, parserResult, kaKtFile)
        // A simple class declaration has no smart casts — hint must be null
        assertNull(hint)
    }
}

// Helper extension (mirrors the one in KaSemanticHighlightingVisitorTest)
private inline fun <reified T : com.intellij.psi.PsiElement> org.jetbrains.kotlin.psi.KtFile.findDescendantOfType(): T? {
    var result: T? = null
    accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: com.intellij.psi.PsiElement) {
            if (element is T) { result = element; stopWalking(); return }
            super.visitElement(element)
        }
    })
    return result
}
