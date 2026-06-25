// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.MoveTypeAliasToTopLevelFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that moves a nested `typealias` declaration to the top level.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.ToplevelTypealiasesOnly].
 * Delegates PSI mutation to [MoveTypeAliasToTopLevelFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (TOPLEVEL_TYPEALIASES_ONLY)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaMoveTypeAliasToTopLevelFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getTypeAlias(): KtTypeAlias? =
        kaError.kaDiagnostic.psi as? KtTypeAlias

    override fun isApplicable(): Boolean =
        kaError.key == "TOPLEVEL_TYPEALIASES_ONLY" && getTypeAlias() != null

    override fun getDescription(): String = "Move type alias to top level"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val alias = getTypeAlias() ?: return
        KaModCommandFix(kaError, MoveTypeAliasToTopLevelFix(alias), alias, doc, kaKtFile, getDescription())
            .implement()
    }
}
