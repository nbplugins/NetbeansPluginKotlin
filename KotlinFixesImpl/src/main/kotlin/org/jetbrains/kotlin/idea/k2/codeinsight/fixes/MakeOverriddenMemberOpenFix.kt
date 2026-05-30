// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from MakeOverriddenMemberOpenFix.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.idea.quickfix.MakeOverriddenMemberOpenFixUtils
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Adds the `open` modifier to overridden declarations that are declared `final`.
 *
 * Corresponds to IntelliJ's private `MakeOverriddenMemberOpenFixFactory.MakeOverriddenMemberOpenFix`.
 * Uses [MakeOverriddenMemberOpenFixUtils] for the actual PSI modifications.
 *
 * @param element the declaration that is trying to override a final member
 * @param overriddenMembers the final declarations that need to be made open
 * @param containingDeclarationNames class names used in [getFamilyName] display text
 */
class MakeOverriddenMemberOpenFix(
    element: KtDeclaration,
    private val overriddenMembers: List<KtCallableDeclaration>,
    containingDeclarationNames: List<String>,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtDeclaration, Unit>(element, Unit) {

    private val familyName: String = MakeOverriddenMemberOpenFixUtils.getActionName(element, containingDeclarationNames)

    override fun invoke(
        actionContext: ActionContext,
        element: KtDeclaration,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        MakeOverriddenMemberOpenFixUtils.invoke(overriddenMembers.map { updater.getWritable(it) })
    }

    override fun getFamilyName(): String = familyName
}
