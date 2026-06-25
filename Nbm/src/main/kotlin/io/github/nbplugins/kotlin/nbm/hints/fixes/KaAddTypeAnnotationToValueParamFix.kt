@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * K2 quick-fix that adds an explicit type annotation to a value parameter
 * that is missing one when it has a default value.
 *
 * Triggered by [KaFirDiagnostic.ValueParameterWithoutExplicitType].
 * Uses K2 analysis inside [implement] to compute the type name from the parameter's
 * default value expression. Delegates PSI mutation to [AddTypeAnnotationToValueParameterFix]
 * from KotlinFixesImpl via [KaModCommandFix].
 *
 * @param kaError the K2 diagnostic (VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE)
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaAddTypeAnnotationToValueParamFix(
    private val kaError: KaDiagnosticError,
    private val doc: Document,
    private val kaKtFile: KtFile,
) : KaQuickFix {

    private fun getParam(): KtParameter? = kaError.kaDiagnostic.psi as? KtParameter

    override fun isApplicable(): Boolean =
        kaError.key == "VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE" && getParam() != null

    override fun getDescription(): String = "Add type annotation"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR), getDescription(), kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition), listOf(fix), Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val param = getParam() ?: return
        val defaultValue = param.defaultValue ?: return
        val typeName = analyze(kaKtFile) {
            val type = defaultValue.expressionType ?: return@analyze null
            if (type.isArrayOrPrimitiveArray) {
                if (param.hasModifier(KtTokens.VARARG_KEYWORD)) {
                    val elemType = type.arrayElementType ?: return@analyze null
                    elemType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                } else if (defaultValue is KtCollectionLiteralExpression) {
                    val elemType = type.arrayElementType
                    if (elemType?.isPrimitive == true) {
                        val classId = (elemType as KaClassType).classId
                        "${classId.shortClassName}Array"
                    } else {
                        type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                    }
                } else {
                    type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                }
            } else {
                type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            }
        } ?: return
        // AddTypeAnnotationToValueParameterFix is private in era 253 — apply the equivalent PSI mutation directly.
        KaModCommandFix.syncMutation(kaKtFile, doc) {
            param.typeReference = KtPsiFactory(param.project).createType(typeName)
        }
    }
}
