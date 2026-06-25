// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddWhenRemainingBranchFixFactories.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.AddRemainingWhenBranchesUtils
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Adds all missing branches to a [when] expression that lacks an `else` or is non-exhaustive.
 *
 * Corresponds to IntelliJ's private `AddWhenRemainingBranchFixFactories.AddRemainingWhenBranchesQuickFix`.
 *
 * @param target the `when` expression missing branches
 * @param context the computed context containing the missing cases
 */
class AddRemainingWhenBranchesQuickFix(
    target: KtWhenExpression,
    private val context: AddRemainingWhenBranchesUtils.ElementContext,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtWhenExpression, Unit>(target, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtWhenExpression,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        AddRemainingWhenBranchesUtils.addRemainingWhenBranches(element, context)
    }

    override fun getFamilyName(): String =
        AddRemainingWhenBranchesUtils.familyAndActionName(context.enumToStarImport != null)
}
