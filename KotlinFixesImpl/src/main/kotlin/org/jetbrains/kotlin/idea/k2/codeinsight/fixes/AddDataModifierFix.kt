// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddDataModifierFixFactory.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass

/**
 * Adds the `data` modifier to a class whose component functions are missing.
 *
 * Corresponds to IntelliJ's private `AddDataModifierFixFactory.AddDataModifierFix`.
 *
 * @param ktClass the class to add the `data` modifier to
 * @param fqName fully-qualified name used in [getFamilyName] display text
 */
class AddDataModifierFix(
    ktClass: KtClass,
    private val fqName: String,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtClass, Unit>(ktClass, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtClass,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        element.addModifier(KtTokens.DATA_KEYWORD)
    }

    override fun getFamilyName(): String = KotlinBundle.message("fix.make.data.class", fqName)
}
