// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi

/**
 * Casts the underlying [KaDiagnosticError.kaDiagnostic] to the requested
 * [KaDiagnosticWithPsi] subtype [D].
 *
 * Used by [KaXxxFix] classes to access typed properties of the diagnostic
 * (e.g. `missingWhenCases` on `KaFirDiagnostic.NoElseInWhen`).
 * Plain-data properties (strings, lists of non-K2 types, PSI elements)
 * are safe to access outside an analysis session; K2 symbol properties
 * must be accessed inside `analyze {}`.
 *
 * @return the diagnostic cast to [D], or `null` if the diagnostic is not of type [D]
 */
inline fun <reified D : KaDiagnosticWithPsi<*>> KaDiagnosticError.typed(): D? =
    kaDiagnostic as? D
