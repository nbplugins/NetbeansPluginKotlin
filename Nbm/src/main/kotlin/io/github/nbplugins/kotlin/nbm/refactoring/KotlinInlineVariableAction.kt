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
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+N** — "Inline Variable" for Kotlin `val` properties.
 *
 * Resolves the property under the caret, creates a [KotlinInlineVariableRefactoring], and
 * opens [KotlinInlineVariableUI] via the NetBeans refactoring framework. The actual analysis
 * and text changes are performed by [KotlinInlineVariablePlugin].
 *
 * Registered under action name `"kotlin-inline-variable"` in `layer.xml` for `text/x-kotlin`.
 *
 * This class belongs to the **controller** layer.
 */
class KotlinInlineVariableAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(SHORT_DESCRIPTION, "Inline Variable")
        putValue(POPUP_MENU_TEXT, "Inline Variable")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return
        if (!fo.hasExt("kt")) return

        val caretPos = target.selectionStart

        // Resolve the property name under the caret for use in the dialog title.
        var variableName = ""
        val ktFile = KotlinPsiManager.getParsedFile(fo)
        if (ktFile != null) {
            val element = ktFile.findElementAt(caretPos)
            val prop = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
            if (prop != null) variableName = prop.name ?: ""
        }

        val refactoring = KotlinInlineVariableRefactoring(doc, caretPos)
        UI.openRefactoringUI(
            KotlinInlineVariableUI(variableName, refactoring),
            TopComponent.getRegistry().getActivated()
        )
    }

    companion object {
        /** Editor action name, used as the keybinding target in KotlinKeyBindings.xml. */
        const val ACTION_NAME = "kotlin-inline-variable"
    }
}
