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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinDeclarationNameValidator
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameValidatorProvider
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * K2-based implementation of [KotlinNameValidatorProvider] for standalone NetBeans sessions.
 *
 * Registered in [io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.registerStandaloneServices].
 * Called by [org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider] to validate
 * candidate names during refactorings (e.g. Extract Function parameter naming).
 */
class KotlinNameValidatorProviderImpl : KotlinNameValidatorProvider {
    override fun createNameValidator(
        container: PsiElement,
        target: KotlinNameSuggestionProvider.ValidatorTarget,
        anchor: PsiElement?,
        excludedDeclarations: List<KtDeclaration>,
    ): (String) -> Boolean = { name ->
        val context = (anchor ?: container)
            .parentsWithSelf
            .filterIsInstance<KtElement>()
            .first()

        val validator = KotlinDeclarationNameValidator(
            visibleDeclarationsContext = context,
            checkVisibleDeclarationsContext = anchor != null,
            target = target,
            excludedDeclarations = excludedDeclarations,
        )

        analyze(context) { validator.validate(name) }
    }
}
