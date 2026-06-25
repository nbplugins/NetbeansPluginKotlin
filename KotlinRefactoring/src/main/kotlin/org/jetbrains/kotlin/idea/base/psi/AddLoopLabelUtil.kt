// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Stub: replaces com.intellij.util.text.UniqueNameGenerator (not in standalone classpath) with
// an equivalent manual implementation.

package org.jetbrains.kotlin.idea.base.psi

import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.parents

object AddLoopLabelUtil {
    fun getUniqueLabelName(loop: KtLoopExpression): String {
        val usedLabels = mutableSetOf<String>()
        loop.acceptChildren(object : KtTreeVisitorVoid() {
            override fun visitLabeledExpression(expression: KtLabeledExpression) {
                super.visitLabeledExpression(expression)
                expression.getLabelName()?.let { usedLabels.add(it) }
            }
        })
        loop.parents.forEach { if (it is KtLabeledExpression) it.getLabelName()?.let { name -> usedLabels.add(name) } }
        var name = "loop"
        var idx = 0
        while (name in usedLabels) name = "loop${++idx}"
        return name
    }

    fun getExistingLabelName(loop: KtLoopExpression): String? =
        (loop.parent as? KtLabeledExpression)?.getLabelName()
}
