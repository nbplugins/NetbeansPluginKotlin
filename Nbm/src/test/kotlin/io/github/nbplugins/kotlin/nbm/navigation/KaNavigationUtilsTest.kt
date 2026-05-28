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
package io.github.nbplugins.kotlin.nbm.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.navigation.getReferenceExpression as getRefPsiElement
import org.jetbrains.kotlin.psi.KtReferenceExpression
import utils.KotlinTestCase
import utils.getCaret
import utils.getDocumentForFileObject

/**
 * Tests for [KaNavigationUtils] — K2 Analysis API navigation utilities.
 *
 * Uses the `navigation/` test project resources to verify that K2 reference resolution
 * produces a PSI source element and that declaration tooltips are non-null and non-empty.
 */
class KaNavigationUtilsTest : KotlinTestCase("KaNavigationUtils test", "navigation") {

    /**
     * Verifies that [KaNavigationUtils.resolveToSourcePsi] returns a non-null PSI element
     * when navigating to a top-level function declared in the same project.
     */
    fun testResolveToSourcePsiForFunction() {
        val doc = getDocumentForFileObject(dir, "checkNavigationToFunction.kt")
        val offset = getCaret(doc) + "<caret>".length + 1
        val psi = getRefPsiElement(doc, offset)
        assertNotNull("PSI element at caret must be found", psi)

        val refExpr = psi!!.toReferenceExpression()
        assertNotNull("Reference expression must be found", refExpr)

        val file = dir.getFileObject("checkNavigationToFunction.kt")
        assertNotNull("FileObject must exist", file)

        val result = KaNavigationUtils.resolveToSourcePsi(refExpr!!, project, file!!)
        assertNotNull("K2 should resolve function reference to a source PSI element", result)
    }

    /**
     * Verifies that [KaNavigationUtils.resolveToSourcePsi] returns a non-null PSI element
     * when navigating to a class declared in the same project.
     */
    fun testResolveToSourcePsiForClass() {
        val doc = getDocumentForFileObject(dir, "checkNavigationToClass.kt")
        val offset = getCaret(doc) + "<caret>".length + 1
        val psi = getRefPsiElement(doc, offset)
        assertNotNull("PSI element at caret must be found", psi)

        val refExpr = psi!!.toReferenceExpression()
        assertNotNull("Reference expression must be found", refExpr)

        val file = dir.getFileObject("checkNavigationToClass.kt")
        assertNotNull("FileObject must exist", file)

        val result = KaNavigationUtils.resolveToSourcePsi(refExpr!!, project, file!!)
        assertNotNull("K2 should resolve class reference to a source PSI element", result)
    }

    /**
     * Verifies that [KaNavigationUtils.renderDeclarationTooltip] returns a non-empty string
     * for a resolvable function reference.
     */
    fun testRenderDeclarationTooltipForFunction() {
        val doc = getDocumentForFileObject(dir, "checkNavigationToFunction.kt")
        val offset = getCaret(doc) + "<caret>".length + 1
        val psi = getRefPsiElement(doc, offset) ?: return
        val refExpr = psi.toReferenceExpression() ?: return

        val file = dir.getFileObject("checkNavigationToFunction.kt")
        assertNotNull("FileObject must exist", file)

        val tooltip = KaNavigationUtils.renderDeclarationTooltip(refExpr, project, file!!)
        assertNotNull("K2 should render a tooltip for a function reference", tooltip)
        assertTrue("Tooltip must be non-empty", tooltip!!.isNotEmpty())
    }
}

private fun PsiElement.toReferenceExpression(): KtReferenceExpression? =
    PsiTreeUtil.getNonStrictParentOfType(this, KtReferenceExpression::class.java)

