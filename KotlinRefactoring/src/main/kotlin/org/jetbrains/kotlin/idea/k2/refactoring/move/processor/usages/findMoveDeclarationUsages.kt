/*******************************************************************************
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
package org.jetbrains.kotlin.idea.k2.refactoring.move.processor.usages

import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.usages.K2MoveRenameUsageInfo.Companion.markInternalUsages
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.isAncestor

/**
 * Real replacement for IDEA's `K2MoveRenameUsageInfo.find()` (dropped in the Copy Declaration port
 * — see the pom.xml patch removing it — since Copy Declaration only needs internal, from-copy
 * usages). Move Declaration needs external usages too, so this reimplements the essential subset:
 * mark internal usages (imports/references inside the moved declaration itself, unchanged from the
 * original), then find every external Kotlin reference via [KotlinMoveUsageSearchService].
 *
 * Deliberately narrower than IDEA's `findExternalUsages`: Java-interop (light-class) usages are
 * out of scope (this plugin's Kotlin light classes are not fully functional standalone, same
 * tradeoff as [org.jetbrains.kotlin.idea.k2.refactoring.move.processor.conflict.visibilityConflict]'s
 * `lightIsVisibleTo`), and `MoveClassHandler`-based usage preprocessing (import-alias cleanup) is
 * not ported — its absence only means an occasional stale import-alias survives a move, not a
 * broken reference.
 */
internal fun findMoveDeclarationUsages(declaration: KtNamedDeclaration): List<UsageInfo> {
    markInternalUsages(declaration, declaration)
    val references = KotlinMoveUsageSearchService.getInstance()?.findUsages(declaration).orEmpty()
    return references
        .filterIsInstance<KtReference>()
        .filter { !declaration.isAncestor(it.element) }
        .mapNotNull { ref ->
            val element = ref.element as? KtElement ?: return@mapNotNull null
            K2MoveRenameUsageInfo.Source(element, ref, declaration, false)
        }
}
