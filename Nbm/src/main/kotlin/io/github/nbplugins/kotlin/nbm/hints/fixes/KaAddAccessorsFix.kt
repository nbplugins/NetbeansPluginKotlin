// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.k2.codeinsight.fixes.AddAccessorsQuickFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds getter and/or setter accessors to a property
 * that must be initialized or be abstract but has neither.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.MustBeInitialized]
 * or [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.MustBeInitializedOrBeAbstract].
 * Delegates PSI mutation to [AddAccessorsQuickFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (MUST_BE_INITIALIZED or MUST_BE_INITIALIZED_OR_BE_ABSTRACT)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddAccessorsFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private val applicableKeys = setOf("MUST_BE_INITIALIZED", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT")

    private fun getProperty(): KtProperty? =
        kaError.kaDiagnostic.psi as? KtProperty

    private fun needsGetter(property: KtProperty): Boolean = property.getter == null

    private fun needsSetter(property: KtProperty): Boolean = property.isVar && property.setter == null

    override fun isApplicable(): Boolean {
        if (kaError.key !in applicableKeys) return false
        val property = getProperty() ?: return false
        return needsGetter(property) || needsSetter(property)
    }

    override fun getDescription(): String {
        val property = getProperty()
        return when {
            property == null -> "Add accessors"
            needsGetter(property) && needsSetter(property) -> "Add getter and setter"
            needsGetter(property) -> "Add getter"
            else -> "Add setter"
        }
    }

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val property = getProperty() ?: return
        val addGetter = needsGetter(property)
        val addSetter = needsSetter(property)
        if (!addGetter && !addSetter) return
        KaModCommandFix(
            kaError, AddAccessorsQuickFix(property, addGetter, addSetter), property, Unit, doc, kaKtFile, getDescription()
        ).implement()
    }
}
