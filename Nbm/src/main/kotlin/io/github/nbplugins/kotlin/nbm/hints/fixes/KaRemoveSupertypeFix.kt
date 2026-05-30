// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.RemoveSupertypeFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that removes a redundant supertype entry when a class inherits
 * from more than one concrete class.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.ManyClassesInSupertypeList].
 * Navigates from the diagnostic psi element to the enclosing [KtSuperTypeListEntry].
 * Delegates PSI mutation to [RemoveSupertypeFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (MANY_CLASSES_IN_SUPERTYPE_LIST)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaRemoveSupertypeFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getSuperTypeEntry(): KtSuperTypeListEntry? =
        kaError.kaDiagnostic.psi.getStrictParentOfType<KtSuperTypeListEntry>()

    override fun isApplicable(): Boolean =
        kaError.key == "MANY_CLASSES_IN_SUPERTYPE_LIST" && getSuperTypeEntry() != null

    override fun getDescription(): String = "Remove supertype"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val entry = getSuperTypeEntry() ?: return
        KaModCommandFix(kaError, RemoveSupertypeFix(entry), entry, Unit, doc, kaKtFile, getDescription())
            .implement()
    }
}
