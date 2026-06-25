// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.ChangeToFunctionInvocationFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.psiUtil.getNextSiblingIgnoringWhitespaceAndComments
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that converts a property reference to a function invocation
 * when the expression requires a function call.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.FunctionCallExpected].
 * If the expression is followed by a type argument list inside a call, the parent call expression
 * is used as the fix target (matching the IntelliJ factory logic).
 * Delegates PSI mutation to [ChangeToFunctionInvocationFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (FUNCTION_CALL_EXPECTED)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaChangeToFunctionInvocationFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getExpression(): KtExpression? {
        val expr = kaError.kaDiagnostic.psi as? KtExpression ?: return null
        val next = expr.getNextSiblingIgnoringWhitespaceAndComments()
        val parent = expr.parent
        return if (next is KtTypeArgumentList && parent is KtCallExpression) parent else expr
    }

    override fun isApplicable(): Boolean =
        kaError.key == "FUNCTION_CALL_EXPECTED" && getExpression() != null

    override fun getDescription(): String = "Change to function invocation"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val expr = getExpression() ?: return
        KaModCommandFix(kaError, ChangeToFunctionInvocationFix(expr), expr, doc, kaKtFile, getDescription())
            .implement()
    }
}
