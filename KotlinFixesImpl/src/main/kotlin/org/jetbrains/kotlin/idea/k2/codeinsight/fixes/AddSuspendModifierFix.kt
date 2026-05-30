// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddSuspendModifierFixFactory.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtModifierListOwner

/**
 * Adds the `suspend` modifier to a function that performs an illegal suspend function call.
 *
 * Corresponds to IntelliJ's private `AddSuspendModifierFixFactory.AddSuspendModifierFix`.
 *
 * @param element the function or lambda to mark as `suspend`
 * @param functionName simple name for display text
 */
class AddSuspendModifierFix(
    element: KtModifierListOwner,
    private val functionName: String,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtModifierListOwner, Unit>(element, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtModifierListOwner,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.addModifier(KtTokens.SUSPEND_KEYWORD)
    }

    override fun getFamilyName(): String =
        KotlinBundle.message("fix.add.suspend.modifier.function", functionName)
}
