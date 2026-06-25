// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds `noinline` or `crossinline` to a lambda parameter
 * that is used in a way incompatible with inlining.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.UsageIsNotInlinable]
 * (adds `noinline`) or [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.NonLocalReturnNotAllowed]
 * (adds `crossinline`).
 * Delegates PSI mutation to [AddInlineModifierFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (USAGE_IS_NOT_INLINABLE or NON_LOCAL_RETURN_NOT_ALLOWED)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddInlineModifierFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private val modifier: KtModifierKeywordToken get() = when (kaError.key) {
        "NON_LOCAL_RETURN_NOT_ALLOWED" -> KtTokens.CROSSINLINE_KEYWORD
        else -> KtTokens.NOINLINE_KEYWORD
    }

    private fun getParam(): KtParameter? {
        val ref = kaError.kaDiagnostic.psi as? KtNameReferenceExpression ?: return null
        return ref.findParameterWithName(ref.getReferencedName())
    }

    override fun isApplicable(): Boolean =
        (kaError.key == "USAGE_IS_NOT_INLINABLE" || kaError.key == "NON_LOCAL_RETURN_NOT_ALLOWED")
            && getParam() != null

    override fun getDescription(): String = "Add '${modifier.value}' modifier"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val param = getParam() ?: return
        // AddInlineModifierFix is private in era 253 — apply the equivalent PSI mutation directly.
        KaModCommandFix.syncMutation(kaKtFile, doc) {
            param.addModifier(modifier)
            if (modifier === KtTokens.NOINLINE_KEYWORD) param.removeModifier(KtTokens.CROSSINLINE_KEYWORD)
        }
    }

    companion object {
        /** Walks PSI parents to find a function parameter with the given name. */
        private fun KtNameReferenceExpression.findParameterWithName(name: String): KtParameter? {
            val function = getStrictParentOfType<KtFunction>() ?: return null
            return function.valueParameters.firstOrNull { it.name == name }
                ?: function.findParameterWithName(name)
        }

        private fun KtFunction.findParameterWithName(name: String): KtParameter? {
            val outer = getStrictParentOfType<KtFunction>() ?: return null
            return outer.valueParameters.firstOrNull { it.name == name }
                ?: outer.findParameterWithName(name)
        }
    }
}
