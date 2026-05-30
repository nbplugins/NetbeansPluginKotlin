// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddFunModifierFixFactory.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass

/**
 * Adds the `fun` modifier to a SAM interface used as a function.
 *
 * Corresponds to IntelliJ's private `AddFunModifierFixFactory.AddFunModifierFix`.
 * Simplified: skips the SAM constructor call replacement (no IntelliJ template support).
 *
 * @param ktClass the interface class to add `fun` to
 * @param elementName simple name of the interface, used in display text
 */
class AddFunModifierFix(
    ktClass: KtClass,
    private val elementName: String,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtClass, Unit>(ktClass, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtClass,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.addModifier(KtTokens.FUN_KEYWORD)
        // SAM constructor call replacement skipped — no IntelliJ live template in NetBeans
    }

    override fun getFamilyName(): String = KotlinBundle.message("add.fun.modifier.to.0", elementName)
}
