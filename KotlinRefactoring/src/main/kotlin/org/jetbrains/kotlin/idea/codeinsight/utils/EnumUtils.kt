// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Stub: removed languageVersionSettings + isEnumValuesSoftDeprecateEnabled (IDE-only) from canBeReferenceToBuiltInEnumFunction.
package org.jetbrains.kotlin.idea.codeinsight.utils

import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

val ENUM_STATIC_METHOD_NAMES = listOf(StandardNames.ENUM_VALUES, StandardNames.ENUM_VALUE_OF)
val JAVA_ENUM_ENTRIES_NAME: Name = Name.identifier("getEntries")
val ENUM_STATIC_METHOD_NAMES_WITH_ENTRIES = ENUM_STATIC_METHOD_NAMES + StandardNames.ENUM_ENTRIES
val ENUM_STATIC_METHOD_NAMES_WITH_ENTRIES_IN_JAVA = ENUM_STATIC_METHOD_NAMES + JAVA_ENUM_ENTRIES_NAME

fun KtQualifiedExpression.canBeReferenceToBuiltInEnumFunction(): Boolean {
    val callExpression = this.callExpression
    val callExpressionName = (callExpression?.calleeExpression as? KtSimpleNameExpression)?.getReferencedNameAsName()
    if (callExpressionName == StandardNames.ENUM_VALUES && callExpression.valueArguments.isEmpty()) return true
    if (callExpressionName == StandardNames.ENUM_VALUE_OF && callExpression.valueArguments.size == 1) return true
    val selectorExpressionName = (this.selectorExpression as? KtSimpleNameExpression)?.getReferencedNameAsName()
    if (selectorExpressionName in ENUM_STATIC_METHOD_NAMES && this.parentOfType<KtImportDirective>() != null) return true
    return false
}

fun KtCallableReferenceExpression.canBeReferenceToBuiltInEnumFunction(): Boolean {
    return this.callableReference.getReferencedNameAsName() in ENUM_STATIC_METHOD_NAMES_WITH_ENTRIES
}
