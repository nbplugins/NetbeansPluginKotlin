// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.AddReturnExpressionFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds a `return` expression to a function with a block body
 * that is missing a return statement.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.NoReturnInFunctionWithBlockBody].
 * Delegates PSI mutation to [AddReturnExpressionFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddReturnExpressionFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getFunction(): KtNamedFunction? {
        val function = kaError.kaDiagnostic.psi as? KtNamedFunction ?: return null
        if (function.bodyBlockExpression?.rBrace == null) return null
        return function
    }

    override fun isApplicable(): Boolean =
        kaError.key == "NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY" && getFunction() != null

    override fun getDescription(): String = "Add return expression"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val function = getFunction() ?: return
        KaModCommandFix(kaError, AddReturnExpressionFix(function), function, doc, kaKtFile, getDescription())
            .implement()
    }
}
