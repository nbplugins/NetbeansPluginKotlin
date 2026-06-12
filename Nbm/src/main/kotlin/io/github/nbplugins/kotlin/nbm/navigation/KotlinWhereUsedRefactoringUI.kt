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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.api.WhereUsedQuery
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI
import org.openide.filesystems.FileObject
import org.openide.util.HelpCtx
import javax.swing.event.ChangeListener
import javax.swing.text.StyledDocument

/**
 * [RefactoringUI] adapter for a Kotlin [WhereUsedQuery] (Find Usages).
 *
 * This class provides a display name for the Find Usages dialog and delivers the prepared
 * [WhereUsedQuery] to the NetBeans refactoring infrastructure.  It has no interactive options
 * panel ([getPanel] returns `null`), so the Find Usages result panel appears immediately.
 *
 * @param query the [WhereUsedQuery] carrying the cursor context
 * @param fileObject the `.kt` file containing the cursor
 * @param offset the cursor offset within [fileObject]
 */
class KotlinWhereUsedRefactoringUI(
    private val query: WhereUsedQuery,
    private val fileObject: FileObject,
    private val offset: Int
) : RefactoringUI {

    private val symbolName: String by lazy { resolveSymbolName() }

    override fun isQuery(): Boolean = true

    override fun getRefactoring(): WhereUsedQuery = query

    /** Returns `null` — no options panel is needed for Find Usages. */
    override fun getPanel(parent: ChangeListener?): CustomRefactoringPanel? = null

    override fun hasParameters(): Boolean = false

    override fun getName(): String = "Find Usages of '$symbolName'"

    override fun getDescription(): String = "Find all usages of '$symbolName' in the project"

    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    override fun setParameters(): Problem? = null

    override fun checkParameters(): Problem? = null

    /**
     * Resolves the symbol name at [offset] using the K2 Analysis API.
     *
     * Falls back to the identifier text at the cursor, or to the file name if resolution fails.
     *
     * @return the short name of the symbol (e.g. "myFunction", "MyClass")
     */
    private fun resolveSymbolName(): String {
        return try {
            resolveSymbolNameOrNull() ?: fileObject.name
        } catch (e: Exception) {
            KotlinLogger.INSTANCE.logException("KotlinWhereUsedRefactoringUI: resolveSymbolName failed", e)
            fileObject.name
        }
    }

    private fun resolveSymbolNameOrNull(): String? {
        val project = ProjectUtils.getKotlinProjectForFileObject(fileObject) ?: return null
        val session = KotlinAnalysisAPISession.getSession(project)
        val ktFile = session.getKtFileForPath(fileObject.path) ?: return null
        val element = ktFile.findElementAt(offset) ?: return null

        // Try resolving a reference expression → its declaration name
        val refExpr = PsiTreeUtil.getNonStrictParentOfType(element, KtReferenceExpression::class.java)
        if (refExpr != null) {
            val name: String? = analyze(ktFile) {
                (refExpr.mainReference?.resolveToSymbol() as? KaNamedSymbol)?.name?.asString()
            }
            if (name != null) return name
        }

        // Cursor on a declaration name itself
        val namedDecl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)
        return namedDecl?.getName() ?: element.text
    }
}
