// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.TooLongCharLiteralToStringFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that converts an overly long character literal (or an illegal escape)
 * to a String literal.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.TooManyCharactersInCharacterLiteral]
 * or [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.IllegalEscape].
 * Delegates PSI mutation to [TooLongCharLiteralToStringFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL or ILLEGAL_ESCAPE)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaTooLongCharLiteralFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getFix(): TooLongCharLiteralToStringFix? {
        val element = kaError.kaDiagnostic.psi as? KtConstantExpression ?: return null
        return TooLongCharLiteralToStringFix.createIfApplicable(element) as? TooLongCharLiteralToStringFix
    }

    override fun isApplicable(): Boolean =
        (kaError.key == "TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL" || kaError.key == "ILLEGAL_ESCAPE")
            && getFix() != null

    override fun getDescription(): String = "Convert character literal to string"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val element = kaError.kaDiagnostic.psi as? KtConstantExpression ?: return
        val ideaFix = TooLongCharLiteralToStringFix.createIfApplicable(element) as? TooLongCharLiteralToStringFix ?: return
        KaModCommandFix(kaError, ideaFix, element, doc, kaKtFile, getDescription()).implement()
    }
}
