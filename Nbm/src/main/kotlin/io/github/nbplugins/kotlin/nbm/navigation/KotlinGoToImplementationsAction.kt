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
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.utils.LineEndUtil
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.openide.awt.StatusDisplayer
import org.openide.filesystems.FileUtil
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.text.JTextComponent

/**
 * Editor action for **Ctrl+Alt+B** — "Go to Implementation" for Kotlin symbols.
 *
 * Registered under action name `"goto-implementation"` in `layer.xml` for `text/x-kotlin`.
 * When triggered, scans all Kotlin source files in the project for declarations that override
 * the symbol at the caret. Navigates directly if there is one result; shows a status message
 * if none are found. (Multiple results: navigates to the first found implementation.)
 *
 * Uses the same K2 scan strategy as [KotlinOverridingMethods.overriddenBy].
 *
 * This class belongs to the **controller** layer.
 */
class KotlinGoToImplementationsAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(SHORT_DESCRIPTION, "Go to Implementation")
        putValue(POPUP_MENU_TEXT, "Go to Implementation")
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
            val targetDecl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java) ?: run {
                StatusDisplayer.getDefault().setStatusText("No implementation found")
                return
            }

            val allFiles = KotlinPsiManager.getFilesByProject(project)
            val implementations = mutableListOf<Pair<KtNamedDeclaration, org.openide.filesystems.FileObject>>()

            for (searchFo in allFiles) {
                val searchFile = session.getKtFileForPath(searchFo.path) ?: continue
                runCatching {
                    analyze(searchFile) {
                        searchFile.accept(object : KtTreeVisitorVoid() {
                            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                                super.visitNamedDeclaration(declaration)
                                if (declaration == targetDecl) return
                                val symbol = runCatching { declaration.symbol as? KaCallableSymbol }.getOrNull() ?: return
                                val overrides = runCatching {
                                    symbol.allOverriddenSymbols.any { it.psi == targetDecl }
                                }.getOrNull() ?: return
                                if (overrides) {
                                    val implFo = toFileObject(declaration, searchFo) ?: return
                                    implementations.add(Pair(declaration, implFo))
                                }
                            }
                        })
                    }
                }
            }

            when {
                implementations.isEmpty() -> StatusDisplayer.getDefault().setStatusText("No implementations found")
                else -> {
                    val (implDecl, implFo) = implementations.first()
                    val implDoc = ProjectUtils.getDocumentFromFileObject(implFo) ?: return
                    val offset = LineEndUtil.convertCrToDocumentOffset(
                        implDecl.containingFile.text, implDecl.textOffset
                    )
                    openFileAtOffset(implDoc as javax.swing.text.StyledDocument, offset)
                }
            }
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinGoToImplementationsAction failed", e)
        }
    }

    private fun toFileObject(psi: com.intellij.psi.PsiElement, currentFo: org.openide.filesystems.FileObject): org.openide.filesystems.FileObject? {
        val virtualFile = psi.containingFile.virtualFile ?: return null
        return FileUtil.toFileObject(File(virtualFile.path))
    }

    companion object {
        const val ACTION_NAME = "goto-implementation"
    }
}
