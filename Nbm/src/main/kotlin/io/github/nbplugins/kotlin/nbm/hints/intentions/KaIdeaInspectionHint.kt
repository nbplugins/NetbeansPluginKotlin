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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.project.Project
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.fixes.KaModCommandFix
import io.github.nbplugins.kotlin.nbm.hints.fixes.NoOpModPsiUpdater
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.getElementContext
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinApplicableInspectionBase
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinModCommandQuickFix
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import javax.swing.text.Document

/**
 * Drives an IDEA [KotlinApplicableInspectionBase] inspection compiled from
 * `submodules/IntellijCommunity` as a NetBeans caret hint.
 *
 * IDEA inspections are normally run by the platform's inspection engine, which builds a
 * `ProblemsHolder`, calls the inspection's visitor, and surfaces the resulting
 * `ProblemDescriptor` and its quick fixes in the Alt+Enter menu. NetBeans has no such
 * engine for Kotlin — hints are caret-driven — so this adapter runs the inspection's
 * applicability pipeline directly, in the same order its own `visitTargetElement` does:
 *
 * 1. `isApplicableByPsi` — cheap PSI-only pre-filter.
 * 2. `getApplicableRanges` — element-relative ranges; the caret must fall inside one.
 * 3. `getElementContext` — the K2 Analysis API pass returning the inspection's context,
 *    or null when the inspection is not applicable.
 * 4. `createQuickFix` — the fix to offer, reached by reflection because the inspection
 *    declares it `protected` (the same technique [KaModCommandFix] uses for `invoke`).
 *
 * Applying the fix calls `KotlinModCommandQuickFix.applyFix(Project, E, ModPsiUpdater)`,
 * also `protected`, again by reflection.
 *
 * @param inspection the compiled IDEA inspection to adapt
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position, belonging to [kaKtFile]
 */
class KaIdeaInspectionHint<E : KtElement, C : Any>(
    private val inspection: KotlinApplicableInspectionBase<E, C>,
    doc: Document,
    kaKtFile: KtFile,
    private val element: E,
) : KaApplicableIntention(doc, kaKtFile, element) {

    /**
     * The fix resolved during the last [isApplicable] call.
     *
     * Resolution costs a K2 Analysis API pass, so it is cached rather than repeated in
     * [implement]. NetBeans calls [isApplicable] before [implement] on the same instance.
     */
    private var resolvedFix: KotlinModCommandQuickFix<E>? = null

    /**
     * Reports whether the IDEA inspection applies at [caretOffset].
     *
     * The inspection's own K2 checks run inside [runCatching]: they are the only path in the
     * hint provider that reaches a compiled IDEA inspection by reflection, so a failure here
     * must not take down the whole caret popup. An inspection that cannot be evaluated is
     * simply not offered.
     */
    override fun isApplicable(caretOffset: Int): Boolean = runCatching {
        if (!inspection.isApplicableByPsi(element)) return@runCatching false

        val relativeCaretOffset = caretOffset - element.textRange.startOffset
        if (inspection.getApplicableRanges(element).none { it.containsOffset(relativeCaretOffset) }) {
            return@runCatching false
        }

        val fix = resolveQuickFix() ?: return@runCatching false
        resolvedFix = fix
        true
    }.getOrDefault(false)

    override fun getDescription(): String = resolvedFix?.name ?: inspection.javaClass.simpleName

    override fun implement() {
        val fix = resolvedFix ?: return
        KaModCommandFix.syncMutation(kaKtFile, doc) {
            @Suppress("UNCHECKED_CAST")
            val updater = NoOpModPsiUpdater as ModPsiUpdater
            KaIdeaReflection.call(fix, "applyFix", 3, element.project as Project, element, updater)
        }
    }

    /**
     * Runs the inspection's context and quick-fix resolution.
     *
     * @return the resolved fix, or null when the inspection is not applicable here
     */
    @OptIn(KaExperimentalApi::class)
    private fun resolveQuickFix(): KotlinModCommandQuickFix<E>? {
        val context = inspection.getElementContext(element) ?: return null
        @Suppress("UNCHECKED_CAST")
        return KaIdeaReflection.call(inspection, "createQuickFix", 2, element, context)
            as? KotlinModCommandQuickFix<E>
    }
}
