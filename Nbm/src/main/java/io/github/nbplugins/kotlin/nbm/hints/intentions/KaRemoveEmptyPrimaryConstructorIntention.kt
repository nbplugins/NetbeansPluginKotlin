/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * Removes an empty primary constructor `()` when it has no parameters, annotations, or modifiers,
 * and the class has no secondary constructors.
 * Does not require K2 analysis — pure PSI manipulation.
 */
class KaRemoveEmptyPrimaryConstructorIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var target: KtPrimaryConstructor? = null

    override fun isApplicable(caretOffset: Int): Boolean {
        val ctor = psi.getNonStrictParentOfType(KtPrimaryConstructor::class.java)
            ?: (psi as? KtClass)?.primaryConstructor
            ?: (psi.parent as? KtClass)?.primaryConstructor
            ?: return false
        target = ctor
        return ctor.valueParameters.isEmpty()
            && ctor.annotations.isEmpty()
            && ctor.modifierList?.text?.isBlank() != false
            && ctor.containingClass()?.secondaryConstructors?.isEmpty() != false
    }

    override fun getDescription() = "Remove empty primary constructor"

    override fun implement() {
        val ctor = target ?: return
        doc.remove(ctor.textRange.startOffset, ctor.textLength)
    }
}
