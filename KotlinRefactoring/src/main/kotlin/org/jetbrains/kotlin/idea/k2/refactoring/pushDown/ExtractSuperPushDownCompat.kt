/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package org.jetbrains.kotlin.idea.k2.refactoring.pushDown

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry

/**
 * Finds the direct supertype entry of [sourceClass] represented by [symbol].
 *
 * This is the one pure K2 helper shared with IDEA Push Down that Extract Super's K2 Pull Up helper
 * needs to update constructor and inherited-interface relationships.
 *
 * @param sourceClass class whose supertype list is searched.
 * @param symbol target supertype symbol.
 * @return matching PSI entry, or `null` when absent.
 */
fun KaSession.getSuperTypeEntryBySymbol(
    sourceClass: KtClassOrObject,
    symbol: KaClassSymbol,
): KtSuperTypeListEntry? = sourceClass.superTypeListEntries.firstOrNull {
    it.typeReference?.type?.expandedSymbol == symbol
}
