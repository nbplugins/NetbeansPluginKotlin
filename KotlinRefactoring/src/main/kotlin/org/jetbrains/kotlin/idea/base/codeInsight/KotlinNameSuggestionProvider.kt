// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Stub replacing IDE-heavy original (removes JavaStatisticsManager, NameSuggestionProvider, @Nls type-arg).
package org.jetbrains.kotlin.idea.base.codeInsight

import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtValueArgument

abstract class KotlinNameSuggestionProvider {

    enum class ValidatorTarget {
        PROPERTY,
        PARAMETER,
        VARIABLE,
        FUNCTION,
        CLASS
    }

    protected abstract fun getReturnTypeNames(
        callable: KtCallableDeclaration,
        validator: KotlinNameValidator,
    ): List<String>

    protected abstract fun getNameForArgument(argument: KtValueArgument): String?
}
