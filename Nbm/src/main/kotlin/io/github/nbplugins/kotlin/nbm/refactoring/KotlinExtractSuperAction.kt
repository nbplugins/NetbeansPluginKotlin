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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.ExtractSuperKind
import io.github.nbplugins.kotlin.refactoring.KaExtractSuperComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/** Common editor action implementation for the two Kotlin Extract Super modes. */
abstract class KotlinExtractSuperAction(
    actionName: String,
    private val kind: ExtractSuperKind,
    private val actionLabel: String,
) : BaseAction(actionName, SAVE_POSITION or ABBREV_RESET) {
    init {
        putValue(NAME, "$actionLabel...")
        putValue(SHORT_DESCRIPTION, actionLabel)
        putValue(POPUP_MENU_TEXT, "$actionLabel...")
    }

    /** Resolves IDEA candidates before opening the NetBeans refactoring UI. */
    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val document = target.document as? StyledDocument ?: run {
            KotlinLogger.INSTANCE.logWarning("$actionLabel: active editor has no StyledDocument")
            return
        }
        val caretOffset = target.caretPosition
        KotlinLogger.INSTANCE.logInfo("$actionLabel: invoked at caret=$caretOffset")
        runCatching {
            val sourceFile = ProjectUtils.getFileObjectForDocument(document)
                ?: error("$actionLabel: no FileObject for editor document")
            val project = ProjectUtils.getKotlinProjectForFileObject(sourceFile)
                ?: ProjectUtils.getValidProject()
                ?: error("$actionLabel: no Kotlin project for ${sourceFile.path}")
            KotlinLogger.INSTANCE.logInfo("$actionLabel: source=${sourceFile.path}, project=${project.projectDirectory.path}")
            val discovery = discover(project, sourceFile, caretOffset)
                ?: error("$actionLabel: discovery is not applicable at caret=$caretOffset")
            KotlinLogger.INSTANCE.logInfo(
                "$actionLabel: discovery class=${discovery.sourceName}, classOffset=${discovery.classOffset}, " +
                    "members=${discovery.members.size}",
            )
            val refactoring = KotlinExtractSuperRefactoring(document, caretOffset, kind).apply {
                classOffset = discovery.classOffset
            }
            UI.openRefactoringUI(
                KotlinExtractSuperUI(discovery, refactoring, KotlinPackageTarget(project, sourceFile), actionLabel),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("$actionLabel action failed", error) }
    }

    /** @return the real IDEA member candidates when the caret belongs to a Kotlin class. */
    private fun discover(
        project: org.netbeans.api.project.Project,
        file: org.openide.filesystems.FileObject,
        caretOffset: Int,
    ): KaExtractSuperComputer.Discovery.Ready? {
        val ktFile = KotlinAnalysisAPISession.getSession(project).getKtFileForPath(file.path) ?: return null
        return KaExtractSuperComputer(ktFile, caretOffset).discover() as? KaExtractSuperComputer.Discovery.Ready
    }
}

/** Editor action for Kotlin **Extract Interface**. */
class KotlinExtractInterfaceAction : KotlinExtractSuperAction(
    ACTION_NAME,
    ExtractSuperKind.INTERFACE,
    "Extract Interface",
) {
    companion object {
        /** Layer action name. */
        const val ACTION_NAME = "kotlin-extract-interface"
    }
}

/** Editor action for Kotlin **Extract Superclass**. */
class KotlinExtractSuperclassAction : KotlinExtractSuperAction(
    ACTION_NAME,
    ExtractSuperKind.SUPERCLASS,
    "Extract Superclass",
) {
    companion object {
        /** Layer action name. */
        const val ACTION_NAME = "kotlin-extract-superclass"
    }
}
