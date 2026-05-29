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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.builtins.StandardNames.KOTLIN_REFLECT_FQ_NAME
import org.jetbrains.kotlin.idea.k2.refactoring.util.ConvertReferenceToLambdaUtil
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * Converts a callable reference to an equivalent lambda expression.
 *
 * For example:
 * ```kotlin
 * list.map(Any::toString)
 * ```
 * becomes:
 * ```kotlin
 * list.map { it.toString() }
 * ```
 *
 * Not applicable when the expected type is a `kotlin.reflect.*` type (e.g. `KFunction`),
 * because in that case the reference is needed for reflection.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertReferenceToLambdaIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing callable reference expression. */
    private fun findCallableRef(): KtCallableReferenceExpression? =
        PsiTreeUtil.getParentOfType(psi, KtCallableReferenceExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val expr = findCallableRef() ?: return false
        return analyze(kaKtFile) {
            if (isReflectType(expr)) return@analyze false
            ConvertReferenceToLambdaUtil.prepareLambdaExpressionText(expr) != null
        }
    }

    override fun getDescription(): String = "Convert reference to lambda"

    override fun implement() {
        val expr = findCallableRef() ?: return
        val lambdaText = analyze(kaKtFile) {
            if (isReflectType(expr)) return@analyze null
            ConvertReferenceToLambdaUtil.prepareLambdaExpressionText(expr)
        } ?: return

        val start = expr.textRange.startOffset
        val len = expr.textRange.length
        doc.atomicChange {
            remove(start, len)
            insertString(start, lambdaText, null)
        }
    }

    /** Returns true when the expected type is a kotlin.reflect.* type. */
    private fun KaSession.isReflectType(element: KtCallableReferenceExpression): Boolean {
        val expectedType = element.expectedType ?: return false
        val classId = (expectedType as? KaClassType)?.classId ?: return false
        val packageFqName = classId.packageFqName
        return !packageFqName.isRoot && packageFqName == KOTLIN_REFLECT_FQ_NAME
    }
}
