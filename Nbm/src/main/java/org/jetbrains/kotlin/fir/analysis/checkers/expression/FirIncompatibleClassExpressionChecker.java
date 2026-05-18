/*
 * Temporary workaround for a K2 Analysis API 2.0.21 bug:
 *   KT-75035 "java.lang.IllegalArgumentException: source must not be null" (fixed in 2.1.20-RC)
 *   KT-83463 "FirIncompatibleClassExpressionChecker should not raise an internal error" (fixed in 2.2.0)
 *
 * Root cause: checkSourceElement() calls reporter.reportOn(element.source, ...) without a null
 * guard. For FIR nodes whose source element has no PSI backing (e.g. inferred return types),
 * element.getSource() returns null, which causes KtDiagnosticReportHelpersKt.requireNotNull()
 * to throw IllegalArgumentException, aborting the entire diagnostic collection for the file.
 *
 * Fix: early-return in checkSourceElement() when element.getSource() is null.
 *
 * Remove this file once the plugin is upgraded to kotlin-compiler >= 2.1.20.
 */
package org.jetbrains.kotlin.fir.analysis.checkers.expression;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.AbstractKtSourceElement;
import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter;
import org.jetbrains.kotlin.diagnostics.DiagnosticContext;
import org.jetbrains.kotlin.diagnostics.KtDiagnosticReportHelpersKt;
import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind;
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext;
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors;
import org.jetbrains.kotlin.fir.declarations.utils.DeclarationAttributesKt;
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression;
import org.jetbrains.kotlin.fir.references.FirReferenceUtilsKt;
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol;
import org.jetbrains.kotlin.fir.types.ConeKotlinType;
import org.jetbrains.kotlin.fir.types.FirTypeUtilsKt;
import org.jetbrains.kotlin.fir.types.TypeUtilsKt;
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerAbiStability;
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource;

public final class FirIncompatibleClassExpressionChecker
        extends FirExpressionChecker<FirQualifiedAccessExpression> {

    public static final FirIncompatibleClassExpressionChecker INSTANCE =
            new FirIncompatibleClassExpressionChecker();

    private FirIncompatibleClassExpressionChecker() {
        super(MppCheckerKind.Common);
    }

    @Override
    public void check(
            @NotNull FirQualifiedAccessExpression expression,
            @NotNull CheckerContext context,
            @NotNull DiagnosticReporter reporter
    ) {
        FirCallableSymbol<?> symbol =
                FirReferenceUtilsKt.toResolvedCallableSymbol(expression.getCalleeReference(), false);
        if (symbol == null) return;

        checkType$checkers(symbol.getResolvedReturnType(), expression, context, reporter);

        if (symbol.getReceiverParameter() != null) {
            checkType$checkers(
                    FirTypeUtilsKt.getConeTypeOrNull(symbol.getReceiverParameter().getTypeRef()),
                    expression, context, reporter);
        }

        if (symbol instanceof FirFunctionSymbol) {
            for (FirValueParameterSymbol param : ((FirFunctionSymbol<?>) symbol).getValueParameterSymbols()) {
                checkType$checkers(param.getResolvedReturnTypeRef().getType(), expression, context, reporter);
            }
        }

        checkSourceElement(symbol.getContainerSource(), expression, context, reporter);
    }

    /** Called by {@link org.jetbrains.kotlin.fir.analysis.checkers.type.FirIncompatibleClassTypeChecker}. */
    public final void checkType$checkers(
            @Nullable ConeKotlinType type,
            @NotNull FirElement element,
            @NotNull CheckerContext context,
            @NotNull DiagnosticReporter reporter
    ) {
        if (type == null) return;
        var classSymbol = TypeUtilsKt.toRegularClassSymbol(type, context.getSession());
        if (classSymbol == null) return;
        SourceElement sourceElement = DeclarationAttributesKt.getSourceElement((FirClassLikeSymbol<?>) classSymbol);
        checkSourceElement(sourceElement, element, context, reporter);
    }

    private void checkSourceElement(
            @Nullable SourceElement source,
            @NotNull FirElement element,
            @NotNull CheckerContext context,
            @NotNull DiagnosticReporter reporter
    ) {
        if (!(source instanceof DeserializedContainerSource)) return;
        DeserializedContainerSource containerSource = (DeserializedContainerSource) source;

        // FIX for KT-75035 / KT-83463: element.getSource() is null for FIR nodes without PSI
        // backing (e.g. inferred return types). The original code passes null to reportOn()
        // which throws IllegalArgumentException and aborts all diagnostic collection.
        AbstractKtSourceElement elementSource = (AbstractKtSourceElement) element.getSource();
        if (elementSource == null) return;

        var incompatibility = containerSource.getIncompatibility();
        if (incompatibility != null) {
            KtDiagnosticReportHelpersKt.reportOn(
                    reporter, elementSource,
                    FirErrors.INSTANCE.getINCOMPATIBLE_CLASS(),
                    containerSource.getPresentableString(), incompatibility,
                    (DiagnosticContext) context, null);
        }
        if (containerSource.isPreReleaseInvisible()) {
            KtDiagnosticReportHelpersKt.reportOn(
                    reporter, elementSource,
                    FirErrors.INSTANCE.getPRE_RELEASE_CLASS(),
                    containerSource.getPresentableString(),
                    (DiagnosticContext) context, null);
        }
        if (containerSource.getAbiStability() == DeserializedContainerAbiStability.UNSTABLE) {
            KtDiagnosticReportHelpersKt.reportOn(
                    reporter, elementSource,
                    FirErrors.INSTANCE.getIR_WITH_UNSTABLE_ABI_COMPILED_CLASS(),
                    containerSource.getPresentableString(),
                    (DiagnosticContext) context, null);
        }
    }
}
