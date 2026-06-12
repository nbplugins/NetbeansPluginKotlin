// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.reformatting.format
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.diagnostics.WhenMissingCase
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.AddRemainingWhenBranchesUtils
import org.jetbrains.kotlin.idea.k2.codeinsight.fixes.AddRemainingWhenBranchesQuickFix
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds all missing branches to a non-exhaustive `when` expression.
 *
 * Triggered by [KaFirDiagnostic.NoElseInWhen]. Reads [KaFirDiagnostic.NoElseInWhen.missingWhenCases]
 * directly from the captured diagnostic (no analysis session required for plain data properties).
 * Delegates PSI mutation to [AddRemainingWhenBranchesQuickFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * Note: the enum star-import variant (which adds `import Enum.*`) requires K2 analysis
 * to resolve the enum class ID and is not included in this fix; only the plain branch
 * addition is performed.
 *
 * @param kaError the K2 diagnostic (NO_ELSE_IN_WHEN)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddWhenRemainingBranchFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getWhenExpr(): KtWhenExpression? =
        kaError.kaDiagnostic.psi as? KtWhenExpression

    private fun getMissingCases(): List<WhenMissingCase>? {
        val diag = kaError.typed<KaFirDiagnostic.NoElseInWhen>() ?: return null
        val cases = diag.missingWhenCases
        if (cases.isEmpty() || cases.singleOrNull() == WhenMissingCase.Unknown) return null
        return cases
    }

    override fun isApplicable(): Boolean =
        kaError.key == "NO_ELSE_IN_WHEN"
            && getWhenExpr() != null
            && getMissingCases() != null

    override fun getDescription(): String = "Add remaining when branches"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val whenExpr = getWhenExpr() ?: return
        val missingCases = getMissingCases() ?: return
        val context = AddRemainingWhenBranchesUtils.Context(missingCases, null)
        val rangeStart = whenExpr.textRange.startOffset
        val originalEnd = whenExpr.textRange.endOffset
        val lenBefore = doc.length
        KaModCommandFix(
            kaError,
            AddRemainingWhenBranchesQuickFix(whenExpr, context),
            whenExpr,
            Unit,
            doc,
            kaKtFile,
            getDescription()
        ).implement()
        val newEnd = originalEnd + (doc.length - lenBefore)
        val docText = doc.getText(0, doc.length)
        var lineStart = rangeStart
        while (lineStart > 0 && docText[lineStart - 1] != '\n') lineStart--
        format(doc, rangeStart, lineStart, newEnd)
    }
}
