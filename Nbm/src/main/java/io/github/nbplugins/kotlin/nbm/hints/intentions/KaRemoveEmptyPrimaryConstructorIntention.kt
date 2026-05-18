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
