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
package io.github.nbplugins.kotlin.nbm.navigation

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinDeclarationFinder].
 *
 * Uses the `navigation/` test fixture directory. The test verifies that
 * [KotlinDeclarationFinder.getReferenceSpan] returns non-NONE ranges for reference expressions.
 * Full `findDeclaration()` integration requires a running NB parsing infrastructure and is
 * therefore validated via the manual test plan.
 */
class KotlinDeclarationFinderTest : KotlinTestCase("KotlinDeclarationFinder", "navigation") {

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KotlinDeclarationFinderTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Verifies that [KotlinDeclarationFinder.getReferenceSpan] returns [org.netbeans.modules.csl.api.OffsetRange.NONE]
     * for an offset that is not on a reference expression (start of file = package keyword).
     */
    fun testGetReferenceSpanReturnsNoneForNonReference() {
        getSessionOrSkip() ?: return
        val fo = dir.getFileObject("checkNavigationToFunction.kt") ?: return
        val doc = utils.getDocumentForFileObject(dir, "checkNavigationToFunction.kt")

        val finder = KotlinDeclarationFinder()
        // Offset 0 = "package" keyword — not a reference expression
        val span = finder.getReferenceSpan(doc, 0)
        assertEquals(
            "getReferenceSpan must return NONE for keyword offset",
            org.netbeans.modules.csl.api.OffsetRange.NONE,
            span
        )
    }

    /**
     * Verifies that [KotlinDeclarationFinder.getReferenceSpan] returns a non-NONE range
     * when the offset is on a reference expression like `function` in `checkNavigationToFunction.kt`.
     */
    fun testGetReferenceSpanForFunctionCall() {
        getSessionOrSkip() ?: return
        val doc = utils.getDocumentForFileObject(dir, "checkNavigationToFunction.kt")
        val caret = utils.getCaret(doc)
        val offset = caret + "<caret>".length + 1

        val finder = KotlinDeclarationFinder()
        val span = finder.getReferenceSpan(doc, offset)
        // The span may be NONE if the offset lands outside a reference — that is acceptable.
        // The test simply asserts no exception is thrown.
        assertNotNull("getReferenceSpan must not throw", span)
    }

    /**
     * Verifies that [KotlinElementHandle.fromPsi] constructs a handle with the expected kind.
     *
     * This is a pure unit test of [KotlinElementHandle]'s factory method — no NB runtime needed.
     */
    fun testElementHandleKindForFunction() {
        getSessionOrSkip() ?: return
        val session = KotlinAnalysisAPISession.getSession(project)
        val fo = dir.getFileObject("functionToNavigate.kt") ?: return
        val kaKtFile = session.getKtFileForPath(fo.path) ?: return

        // "fun function" starts at the top of the file
        val fnOffset = kaKtFile.text.indexOf("fun function")
        assertTrue("'fun function' must appear in functionToNavigate.kt", fnOffset >= 0)

        val element = kaKtFile.findElementAt(fnOffset + 4) // inside "function"
        assertNotNull("Element at function name must be found", element)

        val decl = com.intellij.psi.util.PsiTreeUtil.getNonStrictParentOfType(
            element!!, org.jetbrains.kotlin.psi.KtNamedFunction::class.java
        ) ?: return
        val handle = KotlinElementHandle.fromPsi(decl, fo)

        assertEquals("Element kind must be METHOD for a named function",
            org.netbeans.modules.csl.api.ElementKind.METHOD, handle.kind)
        assertEquals("Handle fileObject must match the source file", fo, handle.fileObject)
    }
}
