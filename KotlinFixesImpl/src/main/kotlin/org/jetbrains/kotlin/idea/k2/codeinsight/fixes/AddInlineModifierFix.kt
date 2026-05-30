// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddInlineModifierFixFactories.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Adds `noinline` or `crossinline` modifier to a function parameter.
 *
 * Corresponds to IntelliJ's private `AddInlineModifierFixFactories.AddInlineModifierFix`.
 * When adding `noinline`, also removes the conflicting `crossinline` modifier.
 *
 * @param parameter the parameter to modify
 * @param modifier the modifier token to add ([KtTokens.NOINLINE_KEYWORD] or [KtTokens.CROSSINLINE_KEYWORD])
 */
class AddInlineModifierFix(
    parameter: KtParameter,
    private val modifier: KtModifierKeywordToken,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtParameter, Unit>(parameter, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtParameter,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.addModifier(modifier)
        if (modifier === KtTokens.NOINLINE_KEYWORD) {
            element.removeModifier(KtTokens.CROSSINLINE_KEYWORD)
        }
    }

    override fun getFamilyName(): String = KotlinBundle.message(
        "fix.add.modifier.inline.parameter.text",
        modifier,
        "",
    )
}
