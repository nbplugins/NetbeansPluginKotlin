/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.types.Variance
import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject

/**
 * K2 Analysis API utilities for "Go to Declaration" navigation and tooltip rendering.
 *
 * These functions form the K2 primary path in the navigation pipeline. Each function
 * accepts a K1-parsed [KtReferenceExpression] and a [FileObject], looks up the matching
 * K2-session-owned [org.jetbrains.kotlin.psi.KtFile] via [KotlinAnalysisAPISession], and
 * runs an [analyze] block to resolve references using [org.jetbrains.kotlin.idea.references.mainReference].
 *
 * All functions return `null` when K2 resolution is unavailable (no session, file not indexed,
 * or the reference resolves to a binary-only symbol without a PSI source); callers are
 * expected to fall back to the K1 path in that case.
 *
 * This object belongs to the **service/model** layer and must not reference NetBeans UI APIs.
 */
object KaNavigationUtils {

    /**
     * Resolves [referenceExpression] to its declaration [PsiElement] using the K2 Analysis API.
     *
     * Returns the PSI element of the declaration for Kotlin source symbols (i.e.
     * [org.jetbrains.kotlin.analysis.api.symbols.KaSymbol.psi] is non-null). Returns `null`
     * for binary-only symbols, unresolved references, or when no K2 session is available.
     *
     * @param referenceExpression the reference expression to resolve (K1-parsed)
     * @param project the owning NetBeans project
     * @param file the source file containing the reference
     * @return the declaration PSI element, or null if K2 cannot navigate to it
     */
    fun resolveToSourcePsi(
        referenceExpression: KtReferenceExpression,
        project: Project,
        file: FileObject
    ): PsiElement? = runCatching {
        val session = KotlinAnalysisAPISession.getSession(project) ?: return null
        val kaKtFile = session.getKtFileForPath(file.path) ?: return null
        val offset = referenceExpression.textRange.startOffset
        val kaElement = kaKtFile.findElementAt(offset) ?: return null
        val kaRef = PsiTreeUtil.getNonStrictParentOfType(kaElement, KtReferenceExpression::class.java)
            ?: return null
        analyze(kaKtFile) {
            kaRef.mainReference?.resolveToSymbol()?.psi
        }
    }.getOrElse { e ->
        KotlinLogger.INSTANCE.logException("K2 navigation resolution failed", e)
        null
    }

    /**
     * Renders the declaration signature at [referenceExpression] as a human-readable string
     * for tooltip display, using the K2 Analysis API.
     *
     * Only [KaDeclarationSymbol]s (functions, classes, properties, etc.) are rendered; other
     * symbol kinds (e.g. packages) produce `null`.  The rendered string uses short names.
     *
     * @param referenceExpression the reference expression whose declaration to render (K1-parsed)
     * @param project the owning NetBeans project
     * @param file the source file containing the reference
     * @return the rendered declaration string, or null if unavailable
     */
    fun renderDeclarationTooltip(
        referenceExpression: KtReferenceExpression,
        project: Project,
        file: FileObject
    ): String? = runCatching {
        val session = KotlinAnalysisAPISession.getSession(project) ?: return null
        val kaKtFile = session.getKtFileForPath(file.path) ?: return null
        val offset = referenceExpression.textRange.startOffset
        val kaElement = kaKtFile.findElementAt(offset) ?: return null
        val kaRef = PsiTreeUtil.getNonStrictParentOfType(kaElement, KtReferenceExpression::class.java)
            ?: return null
        analyze(kaKtFile) {
            val symbol = kaRef.mainReference?.resolveToSymbol() as? KaDeclarationSymbol
                ?: return@analyze null
            symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
        }
    }.getOrElse { e ->
        KotlinLogger.INSTANCE.logException("K2 tooltip rendering failed", e)
        null
    }

    /**
     * Returns a smart-cast description string for [expression] using the K2 Analysis API.
     *
     * Returns `null` when the expression has no smart cast, the reference cannot be resolved,
     * or no K2 session is available.
     *
     * @param expression the simple name expression to check for a smart cast (K1-parsed)
     * @param project the owning NetBeans project
     * @param file the source file containing the expression
     * @return a description like "Smart cast to Foo", or null if no smart cast applies
     */
    fun getSmartCastDescription(
        expression: KtSimpleNameExpression,
        project: Project,
        file: FileObject
    ): String? = runCatching {
        val session = KotlinAnalysisAPISession.getSession(project) ?: return null
        val kaKtFile = session.getKtFileForPath(file.path) ?: return null
        val offset = expression.textRange.startOffset
        val kaElement = kaKtFile.findElementAt(offset) ?: return null
        val kaExpr = PsiTreeUtil.getNonStrictParentOfType(kaElement, KtSimpleNameExpression::class.java)
            ?: return null
        analyze(kaKtFile) {
            val smartCastType = kaExpr.smartCastInfo?.smartCastType ?: return@analyze null
            "Smart cast to ${smartCastType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}"
        }
    }.getOrElse { e ->
        KotlinLogger.INSTANCE.logException("K2 smart cast check failed", e)
        null
    }
}
