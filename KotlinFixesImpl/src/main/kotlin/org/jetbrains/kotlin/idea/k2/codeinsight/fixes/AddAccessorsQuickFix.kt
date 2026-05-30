// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2024 nbplugins contributors. Standalone public version extracted from AddAccessorsFactories.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.AddAccessorUtils
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Adds getter and/or setter accessors to a property that requires them.
 *
 * Corresponds to IntelliJ's private `AddAccessorsFactories.AddAccessorsQuickFix`.
 * Caret movement after insertion is skipped (no IntelliJ editor in NetBeans).
 *
 * @param target the property to add accessors to
 * @param addGetter whether to add a getter
 * @param addSetter whether to add a setter
 */
class AddAccessorsQuickFix(
    target: KtProperty,
    private val addGetter: Boolean,
    private val addSetter: Boolean,
) : KotlinPsiUpdateModCommandAction.ElementBased<KtProperty, Unit>(target, Unit) {

    override fun invoke(
        actionContext: ActionContext,
        element: KtProperty,
        elementContext: Unit,
        updater: ModPsiUpdater,
    ) {
        AddAccessorUtils.addAccessors(element, addGetter, addSetter, caretMover = null)
    }

    override fun getFamilyName(): String = AddAccessorUtils.familyAndActionName(addGetter, addSetter)
}
