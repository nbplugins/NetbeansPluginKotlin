// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds the `suspend` modifier to a function that contains
 * an illegal suspend function call.
 *
 * Triggered by [org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic.IllegalSuspendFunctionCall].
 * Navigates to the nearest enclosing named function using PSI parent traversal
 * (simplified — does not follow the IntelliJ inline-lambda heuristic).
 * Delegates PSI mutation to [AddSuspendModifierFix] from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (ILLEGAL_SUSPEND_FUNCTION_CALL)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddSuspendModifierFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getFunction(): KtNamedFunction? {
        val psi = kaError.kaDiagnostic.psi as? KtElement ?: return null
        return psi.getStrictParentOfType<KtNamedFunction>()
    }

    override fun isApplicable(): Boolean =
        kaError.key == "ILLEGAL_SUSPEND_FUNCTION_CALL" && getFunction() != null

    override fun getDescription(): String {
        val name = getFunction()?.name ?: return "Add 'suspend' modifier"
        return "Add 'suspend' modifier to function '$name'"
    }

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val function = getFunction() ?: return
        // AddSuspendModifierFix is private in era 253 — apply the equivalent PSI mutation directly.
        KaModCommandFix.syncMutation(kaKtFile, doc) {
            (function as KtModifierListOwner).addModifier(KtTokens.SUSPEND_KEYWORD)
        }
    }
}
