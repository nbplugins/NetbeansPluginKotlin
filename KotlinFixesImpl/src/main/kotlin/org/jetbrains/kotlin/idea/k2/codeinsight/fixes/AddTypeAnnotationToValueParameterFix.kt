// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddTypeAnnotationToValueParameterFixFactory.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Adds an explicit type annotation to a value parameter that is missing one.
 *
 * Corresponds to IntelliJ's private `AddTypeAnnotationToValueParameterFixFactory.AddTypeAnnotationToValueParameterFix`.
 *
 * @param element the parameter missing the type annotation
 * @param typeName the fully-qualified type name to annotate
 */
class AddTypeAnnotationToValueParameterFix(
    element: KtParameter,
    private val typeName: String,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtParameter, Unit>(element, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtParameter,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.typeReference = KtPsiFactory(actionContext.project).createType(typeName)
    }

    override fun getFamilyName(): String = KotlinBundle.message("fix.add.type.annotation.family")
}
