// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search.ideaExtensions

/**
 * Stub: options bag IDEA's [com.intellij.psi.search.searches.ReferencesSearch] uses to control
 * how Kotlin-specific reference search behaves (overrides, overloads, convention calls, ...).
 *
 * Standalone, reference search is done by [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService]
 * (a whole-project PSI scan, not [com.intellij.psi.search.searches.ReferencesSearch]), so this data
 * class only needs to exist for the ported Change Signature engine (E9.8) to compile against; its
 * field values are not consulted standalone.
 */
data class KotlinReferencesSearchOptions(
    val acceptCallableOverrides: Boolean = false,
    val acceptOverloads: Boolean = false,
    val acceptExtensionsOfDeclarationClass: Boolean = false,
    val acceptCompanionObjectMembers: Boolean = false,
    val acceptImportAlias: Boolean = true,
    val searchForComponentConventions: Boolean = true,
    val searchForOperatorConventions: Boolean = true,
    val searchNamedArguments: Boolean = true,
    val searchForExpectedUsages: Boolean = true
) {
    companion object {
        val Empty = KotlinReferencesSearchOptions()
    }
}
