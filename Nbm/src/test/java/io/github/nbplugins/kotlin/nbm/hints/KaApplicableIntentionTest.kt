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

import com.intellij.psi.PsiElement
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import utils.getDocumentForFileObject

/**
 * Unit tests for [KaApplicableIntention].
 *
 * Verifies the base class contract: [KaApplicableIntention.isSafe] returns true and
 * [KaApplicableIntention.isInteractive] returns false.
 */
class KaApplicableIntentionTest : KotlinTestCase("KaApplicableIntention", "intentions") {

    /**
     * Minimal concrete subclass for testing the abstract base.
     */
    private class TestIntention(
        doc: Document,
        kaKtFile: KtFile,
        psi: PsiElement,
        private val applicable: Boolean
    ) : KaApplicableIntention(doc, kaKtFile, psi) {
        override fun isApplicable(caretOffset: Int): Boolean = applicable
        override fun getDescription(): String = "test"
        override fun implement() {}
    }

    /** isSafe must return true for all intentions. */
    fun testIsSafe() {
        val file = dir.getFileObject("specifyType.kt") ?: return
        val doc = getDocumentForFileObject(dir, "specifyType.kt")
        val kaKtFile = getKaKtFileOrSkip("specifyType", file.path) ?: return

        val psi = org.jetbrains.kotlin.builder.KotlinPsiManager.getParsedFile(file)
            ?.findElementAt(0) ?: return

        val intention = TestIntention(doc, kaKtFile, psi, true)
        assertTrue(intention.isSafe())
    }

    /** isInteractive must return false for all intentions. */
    fun testIsNotInteractive() {
        val file = dir.getFileObject("specifyType.kt") ?: return
        val doc = getDocumentForFileObject(dir, "specifyType.kt")
        val kaKtFile = getKaKtFileOrSkip("specifyType", file.path) ?: return

        val psi = org.jetbrains.kotlin.builder.KotlinPsiManager.getParsedFile(file)
            ?.findElementAt(0) ?: return

        val intention = TestIntention(doc, kaKtFile, psi, true)
        assertFalse(intention.isInteractive())
    }

    private fun getKaKtFileOrSkip(name: String, path: String): KtFile? {
        val wrapper = io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            println("KaApplicableIntentionTest: skipping $name — no K2 dependencies")
            return null
        }
        return wrapper.getKtFileForPath(path)
    }
}
