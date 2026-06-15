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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.navigation

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.utils.LineEndUtil
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.openide.awt.StatusDisplayer
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.text.JTextComponent

/**
 * Editor action for **Ctrl+Shift+P** — "Go to Super Implementation" for Kotlin symbols.
 *
 * Registered under action name `"goto-super-implementation"` in `layer.xml` for
 * `text/x-kotlin`. When triggered, resolves the K2 symbol at the caret and navigates
 * to the first declaration it directly overrides (using [KaCallableSymbol.allOverriddenSymbols]).
 *
 * If the symbol overrides nothing, a status bar message is shown instead of navigating.
 * If multiple super declarations exist, the first one in the override chain is used.
 *
 * This class belongs to the **controller** layer.
 */
class KotlinGoToSuperTypeAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(SHORT_DESCRIPTION, "Go to Super Implementation")
        putValue(POPUP_MENU_TEXT, "Go to Super Implementation")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document ?: return
        val caretOffset = target.caretPosition
        val cancel = AtomicBoolean(false)

        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return

        runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val kaKtFile = session.getKtFileForPath(fo.path) ?: return
            val element = kaKtFile.findElementAt(caretOffset) ?: return

            val decl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)
            if (decl != null) {
                navigateToSuperViaDeclaration(decl, fo, session)
                return
            }

            val refExpr = PsiTreeUtil.getNonStrictParentOfType(element, KtReferenceExpression::class.java)
            if (refExpr != null) {
                val resolved = analyze(kaKtFile) {
                    refExpr.mainReference?.resolveToSymbol()?.psi as? KtNamedDeclaration
                }
                if (resolved != null) {
                    val resolvedFo = findFileObjectForReferencedElement(resolved, refExpr, fo)
                        ?: fo
                    navigateToSuperViaDeclaration(resolved, resolvedFo, session)
                    return
                }
            }
            StatusDisplayer.getDefault().setStatusText("No super implementation found")
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinGoToSuperTypeAction failed", e)
        }
    }

    private fun navigateToSuperViaDeclaration(
        decl: KtNamedDeclaration,
        fo: org.openide.filesystems.FileObject,
        session: KotlinAnalysisAPISession
    ) {
        val kaKtFile = session.getKtFileForPath(fo.path) ?: return
        val superDecl = analyze(kaKtFile) {
            val symbol = decl.symbol as? KaCallableSymbol ?: return
            symbol.allOverriddenSymbols.firstOrNull()?.psi as? KtNamedDeclaration
        } ?: run {
            StatusDisplayer.getDefault().setStatusText("No super implementation found")
            return
        }

        val superFo = findFileObjectForReferencedElement(superDecl, decl, fo) ?: return
        val superDoc = ProjectUtils.getDocumentFromFileObject(superFo) ?: return
        val offset = LineEndUtil.convertCrToDocumentOffset(
            superDecl.containingFile.text, superDecl.textOffset
        )
        openFileAtOffset(superDoc as javax.swing.text.StyledDocument, offset)
    }

    companion object {
        const val ACTION_NAME = "goto-super-implementation"
    }
}
