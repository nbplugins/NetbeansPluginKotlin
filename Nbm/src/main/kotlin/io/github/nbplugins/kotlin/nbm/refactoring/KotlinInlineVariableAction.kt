/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.refactoring

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+N** — "Inline" for Kotlin `fun` declarations and `val`/`var` properties.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 * Dispatches to the function or variable inline UI based on what is under the cursor:
 *  - [KtNamedFunction] → [KotlinInlineFunctionUI] + [KotlinInlineFunctionRefactoring]
 *  - [KtProperty]      → [KotlinInlineVariableUI] + [KotlinInlineVariableRefactoring]
 *
 * The action does **no validation** on its own — the plugin's `prepare()` validates and surfaces
 * a fatal `Problem` if the symbol cannot be inlined. This keeps the action thin and lets the
 * framework display IDEA's error messages verbatim.
 *
 * This class belongs to the **controller** layer.
 */
class KotlinInlineVariableAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        // NAME drives both the Refactor submenu label (Menu/Refactoring shadow) and the
        // command palette; without it the menu shows the bare action-name "kotlin-inline-variable".
        putValue(NAME, "Inline...")
        putValue(SHORT_DESCRIPTION, "Inline")
        putValue(POPUP_MENU_TEXT, "Inline...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val selectionStart = target.selectionStart
        val selectionEnd = target.selectionEnd
        val targetOffset = selectionStart

        runCatching {
            when (val targetDeclaration = resolveTargetAt(doc, selectionStart, selectionEnd)) {
                is KtNamedFunction -> UI.openRefactoringUI(
                    KotlinInlineFunctionUI(targetDeclaration.name.orEmpty(), KotlinInlineFunctionRefactoring(doc, targetOffset)),
                    TopComponent.getRegistry().activated,
                )
                is KtProperty -> UI.openRefactoringUI(
                    KotlinInlineVariableUI(targetDeclaration.name.orEmpty(), KotlinInlineVariableRefactoring(doc, targetOffset)),
                    TopComponent.getRegistry().activated,
                )
                null -> return@runCatching
            }
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinInlineVariableAction failed", e)
        }
    }

    /**
     * Resolves the directly selected inline declaration.
     *
     * A reference resolves through K2 to its declaration; a declaration-position caret uses the
     * directly selected property/function token. Unlike the former function-first lookup, this
     * never treats an arbitrary child of an enclosing function as the function itself.
     *
     * @param doc            Kotlin editor document containing the selection
     * @param selectionStart first selected offset, or the caret offset when empty
     * @param selectionEnd   offset immediately after the selection, or [selectionStart] when empty
     * @return the target property/function, or `null` when the selection has no inline target
     */
    private fun resolveTargetAt(
        doc: StyledDocument,
        selectionStart: Int,
        selectionEnd: Int,
    ): KtNamedDeclaration? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            resolveTargetAt(ktFile, selectionStart, selectionEnd)
        }.getOrNull()
    }

    /** Resolves a direct caret target from a session-managed Kotlin file. */
    internal fun resolveTargetAt(ktFile: KtFile, offset: Int): KtNamedDeclaration? =
        resolveTargetAt(ktFile, offset, offset)

    /**
     * Resolves the property/function covered by a caret or selection in a session-managed Kotlin file.
     *
     * @param selectionStart first selected offset, or the caret offset for an empty selection
     * @param selectionEnd offset immediately after the selection, or [selectionStart] when empty
     * @return directly selected inline target, or `null` when the range selects no inlineable declaration
     */
    internal fun resolveTargetAt(
        ktFile: KtFile,
        selectionStart: Int,
        selectionEnd: Int,
    ): KtNamedDeclaration? {
        val offsets = if (selectionStart < selectionEnd) {
            intArrayOf(selectionStart, selectionEnd - 1)
        } else {
            intArrayOf(selectionStart)
        }
        for (offset in offsets) {
            val element = ktFile.findElementAt(offset) ?: continue
            resolveReferenceTarget(element)?.let { return it }
            ((element.parent as? KtProperty) ?: (element.parent as? KtNamedFunction))?.let { return it }
        }
        return null
    }

    /** Resolves a direct Kotlin reference and retains only declarations supported by Inline. */
    private fun resolveReferenceTarget(element: PsiElement): KtNamedDeclaration? {
        val reference = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            ?: return null
        return runCatching {
            analyze(reference) {
                reference.mainReference?.resolveToSymbol()?.psi as? KtNamedDeclaration
            }
        }.getOrNull()?.takeIf { it is KtProperty || it is KtNamedFunction }
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-inline-variable"
    }
}
