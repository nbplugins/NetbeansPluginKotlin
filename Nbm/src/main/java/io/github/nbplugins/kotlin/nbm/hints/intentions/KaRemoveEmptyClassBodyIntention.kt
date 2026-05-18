package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getNextSiblingIgnoringWhitespaceAndComments
import org.jetbrains.kotlin.psi.KtClass
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * Removes an empty class body `{}` when it contains no declarations.
 * Does not require K2 analysis — pure PSI manipulation.
 */
class KaRemoveEmptyClassBodyIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var target: KtClassBody? = null

    override fun isApplicable(caretOffset: Int): Boolean {
        val body = psi.getNonStrictParentOfType(KtClassBody::class.java)
            ?: (psi as? KtClass)?.body
            ?: (psi.parent as? KtClass)?.body
            ?: return false
        target = body

        body.getNonStrictParentOfType(KtObjectDeclaration::class.java)?.let {
            if (it.isObjectLiteral()) return false
        }
        body.getNonStrictParentOfType(KtClass::class.java)?.let {
            if (!it.isTopLevel() && it.getNextSiblingIgnoringWhitespaceAndComments() is KtSecondaryConstructor) return false
        }
        return body.text.replace("{", "").replace("}", "").isBlank()
    }

    override fun getDescription() = "Remove empty class body"

    override fun implement() {
        val body = target ?: return
        doc.remove(body.textRange.startOffset, body.textLength)
    }
}
