// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.quickfix.AddJvmInlineAnnotationFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds `@JvmInline` to a value class that is missing the annotation.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.ValueClassWithoutJvmInlineAnnotation].
 * Delegates PSI mutation to [AddJvmInlineAnnotationFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (VALUE_CLASS_WITHOUT_JVM_INLINE_ANNOTATION)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddJvmInlineFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getClass(): KtClass? =
        kaError.kaDiagnostic.psi.getParentOfType<KtClass>(strict = true)

    override fun isApplicable(): Boolean =
        kaError.key == "VALUE_CLASS_WITHOUT_JVM_INLINE_ANNOTATION" && getClass() != null

    override fun getDescription(): String = "Add '@JvmInline' annotation"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val klass = getClass() ?: return
        KaModCommandFix(kaError, AddJvmInlineAnnotationFix(klass), klass, Unit, doc, kaKtFile, getDescription())
            .implement()
    }
}
