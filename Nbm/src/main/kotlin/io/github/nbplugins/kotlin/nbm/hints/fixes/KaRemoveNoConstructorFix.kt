// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.RemoveNoConstructorFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that removes a supertype constructor call when the supertype
 * has no constructor (e.g. an interface referenced with parentheses).
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.NoConstructor].
 * Delegates PSI mutation to [RemoveNoConstructorFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (NO_CONSTRUCTOR)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaRemoveNoConstructorFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getEntry(): KtSuperTypeCallEntry? =
        kaError.kaDiagnostic.psi as? KtSuperTypeCallEntry

    override fun isApplicable(): Boolean =
        kaError.key == "NO_CONSTRUCTOR" && getEntry() != null

    override fun getDescription(): String = "Remove constructor invocation"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val entry = getEntry() ?: return
        KaModCommandFix(kaError, RemoveNoConstructorFix(entry), entry, Unit, doc, kaKtFile, getDescription())
            .implement()
    }
}
