// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddValVarToConstructorParameterFixFactory.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Adds a `val` keyword to a constructor parameter that is missing `val` or `var`.
 *
 * Corresponds to IntelliJ's private `AddValVarToConstructorParameterFixFactory.AddValVarToConstructorParameterFix`.
 * Simplified: always inserts `val`; the IntelliJ version uses a live template to let the user
 * choose between `val` and `var`, which is not supported in NetBeans.
 *
 * @param element the constructor parameter to promote to a property
 */
class AddValVarToConstructorParameterFix(
    element: KtParameter,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtParameter, Unit>(element, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtParameter,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.addBefore(KtPsiFactory(actionContext.project).createValKeyword(), element.nameIdentifier)
    }

    override fun getFamilyName(): String = KotlinBundle.message("fix.add.val.var.to.parameter")
}
