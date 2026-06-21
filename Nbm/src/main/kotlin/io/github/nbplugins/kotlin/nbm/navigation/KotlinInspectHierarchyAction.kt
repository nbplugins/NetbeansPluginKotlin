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
package io.github.nbplugins.kotlin.nbm.navigation

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.openide.awt.StatusDisplayer
import org.openide.filesystems.FileUtil
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Editor action for **Alt+Shift+F12** — "Inspect Hierarchy" for Kotlin types.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 * When triggered, resolves the class or interface at the caret (or the enclosing class of any
 * member at the caret) and opens [KotlinTypeHierarchyTopComponent] with that class as the
 * focal node.
 *
 * This class belongs to the **controller** layer.
 */
class KotlinInspectHierarchyAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(SHORT_DESCRIPTION, "Inspect Hierarchy")
        putValue(POPUP_MENU_TEXT, "Inspect Hierarchy")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document ?: return
        val caretOffset = target.caretPosition

        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return

        runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val kaKtFile = session.getKtFileForPath(fo.path) ?: return
            val element = kaKtFile.findElementAt(caretOffset) ?: return

            // Prefer a class/interface declaration at the caret; fall back to the enclosing class.
            val classDecl: KtClassOrObject = PsiTreeUtil.getNonStrictParentOfType(
                element, KtClassOrObject::class.java
            ) ?: run {
                val memberDecl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)
                PsiTreeUtil.getNonStrictParentOfType(memberDecl, KtClassOrObject::class.java)
            } ?: run {
                StatusDisplayer.getDefault().setStatusText("Place caret on a class or interface")
                return
            }

            val vf = classDecl.containingFile.virtualFile ?: return
            val classFo = FileUtil.toFileObject(File(vf.path)) ?: return
            val rootNode = KotlinTypeHierarchyNode.fromPsi(classDecl, classFo)

            SwingUtilities.invokeLater {
                KotlinTypeHierarchyTopComponent.openFor(rootNode, project)
            }
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinInspectHierarchyAction failed", e)
        }
    }

    companion object {
        /** Key used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-inspect-hierarchy"
    }
}
