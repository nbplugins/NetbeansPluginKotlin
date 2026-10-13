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

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.Presentation
import com.intellij.openapi.util.TextRange
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.fixes.KaModCommandFix
import io.github.nbplugins.kotlin.nbm.hints.fixes.NoOpModPsiUpdater
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinApplicableModCommandAction
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import javax.swing.text.Document

/**
 * Drives an IDEA [KotlinApplicableModCommandAction] intention compiled from
 * `submodules/IntellijCommunity` as a NetBeans caret hint.
 *
 * IDEA decides applicability in [KotlinApplicableModCommandAction.isElementApplicable], which
 * needs an [ActionContext] carrying the absolute caret offset. This adapter builds that context
 * and delegates, so the IDEA implementation — including its K2 Analysis API checks — runs
 * unchanged.
 *
 * Applying the fix goes through [KaModCommandFix], which invokes the action's
 * `invoke(ActionContext, E, ModPsiUpdater)` by reflection and syncs the mutated PSI back to
 * the document.
 *
 * @param action the compiled IDEA intention to adapt
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position, belonging to [kaKtFile]
 */
class KaIdeaIntentionHint<E : KtElement>(
    private val action: KotlinApplicableModCommandAction<E, *>,
    doc: Document,
    kaKtFile: KtFile,
    private val element: E,
) : KaApplicableIntention(doc, kaKtFile, element) {

    /**
     * Reports whether the IDEA action applies at [caretOffset].
     *
     * The action's own K2 checks run inside [runCatching]: they are the only path in the hint
     * provider that reaches a compiled IDEA action by reflection, so a failure here must not
     * take down the whole caret popup. An action that cannot be evaluated is simply not offered.
     */
    override fun isApplicable(caretOffset: Int): Boolean = runCatching {
        action.isElementApplicable(element, actionContext(caretOffset))
    }.getOrDefault(false)

    /**
     * Returns the action's user-visible name.
     *
     * [KotlinApplicableModCommandAction] builds it in `getPresentation`, which is `protected`
     * and only reachable by reflection; when no presentation is produced the family name is
     * the fallback, mirroring IDEA's own behaviour.
     */
    override fun getDescription(): String {
        val presentation = KaIdeaReflection.call(
            action, "getPresentation", 2, actionContext(element.textRange.startOffset), element
        ) as? Presentation
        return presentation?.name() ?: action.familyName
    }

    override fun implement() {
        val ctx = actionContext(element.textRange.startOffset)
        // KotlinPsiUpdateModCommandAction declares
        // invoke(ActionContext, E, C, ModPsiUpdater) — four parameters, with the element
        // context that isElementApplicable computed and discarded. It is resolved again here
        // through the action's own protected getElementContext(ActionContext, E).
        val elementContext = KaIdeaReflection.call(action, "getElementContext", 2, ctx, element)
        KaModCommandFix.syncMutation(kaKtFile, doc) {
            KaIdeaReflection.call(action, "invoke", 4, ctx, element, elementContext, NoOpModPsiUpdater)
        }
    }

    /**
     * Builds the [ActionContext] IDEA expects for [caretOffset].
     *
     * @param caretOffset absolute caret offset in the document
     */
    private fun actionContext(caretOffset: Int) = ActionContext(
        element.project,
        kaKtFile,
        caretOffset,
        TextRange.EMPTY_RANGE,
        element
    )
}
