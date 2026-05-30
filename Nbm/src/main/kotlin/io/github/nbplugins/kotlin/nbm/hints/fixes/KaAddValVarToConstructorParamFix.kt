// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.k2.codeinsight.fixes.AddValVarToConstructorParameterFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds a `val` keyword to a primary constructor parameter
 * that is a data class property but lacks `val` or `var`.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.DataClassNotPropertyParameter].
 * Delegates PSI mutation to [AddValVarToConstructorParameterFix] from KotlinFixesImpl via [KaModCommandFix].
 * Always adds `val` (the interactive val/var choice requires a live editor template).
 *
 * @param kaError the K2 diagnostic (DATA_CLASS_NOT_PROPERTY_PARAMETER)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddValVarToConstructorParamFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getParam(): KtParameter? =
        kaError.kaDiagnostic.psi as? KtParameter

    override fun isApplicable(): Boolean =
        kaError.key == "DATA_CLASS_NOT_PROPERTY_PARAMETER" && getParam() != null

    override fun getDescription(): String = "Add 'val' to primary constructor parameter"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val param = getParam() ?: return
        KaModCommandFix(kaError, AddValVarToConstructorParameterFix(param), param, Unit, doc, kaKtFile, getDescription())
            .implement()
    }
}
