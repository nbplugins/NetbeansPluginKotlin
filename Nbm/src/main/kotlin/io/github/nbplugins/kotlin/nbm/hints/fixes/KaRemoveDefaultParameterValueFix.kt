// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.RemoveDefaultParameterValueFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that removes a default parameter value from an overriding function
 * where default values are not allowed.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.DefaultValueNotAllowedInOverride].
 * The diagnostic psi points to the default value expression; its parent is the [KtParameter].
 * Delegates PSI mutation to [RemoveDefaultParameterValueFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (DEFAULT_VALUE_NOT_ALLOWED_IN_OVERRIDE)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaRemoveDefaultParameterValueFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getParam(): KtParameter? =
        kaError.kaDiagnostic.psi.parent as? KtParameter

    override fun isApplicable(): Boolean =
        kaError.key == "DEFAULT_VALUE_NOT_ALLOWED_IN_OVERRIDE" && getParam() != null

    override fun getDescription(): String = "Remove default parameter value"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val param = getParam() ?: return
        KaModCommandFix(kaError, RemoveDefaultParameterValueFix(param), param, doc, kaKtFile, getDescription())
            .implement()
    }
}
