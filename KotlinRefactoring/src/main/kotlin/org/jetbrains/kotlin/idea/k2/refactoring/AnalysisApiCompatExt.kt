// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(
    kotlin.ExperimentalContextParameters::class,
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
)
package org.jetbrains.kotlin.idea.k2.refactoring

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol

/** Backward-compat: the class containing the original overridden declaration. */
context(KaSession)
val KaCallableSymbol.originalContainingClassForOverride: KaNamedClassSymbol?
    get() = fakeOverrideOriginal.containingSymbol as? KaNamedClassSymbol
