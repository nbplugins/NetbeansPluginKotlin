/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package com.intellij.codeInspection.localCanBeFinal

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.controlFlow.AnalysisCanceledException
import com.intellij.psi.controlFlow.ControlFlow
import com.intellij.psi.controlFlow.ControlFlowFactory
import com.intellij.psi.controlFlow.ControlFlowPolicy
import com.intellij.psi.util.PsiTreeUtil

/**
 * Standalone replacement for the `LocalCanBeFinal` inspection's control-flow helper.
 *
 * IDEA's class lives in `java-analysis-impl`, which this plugin does not bundle. Only the
 * [getControlFlow] entry point is used by the J2K engine
 * (`org.jetbrains.kotlin.nj2k.mutabilityUtils`), and its implementation needs nothing beyond the
 * Java control-flow builder that **is** available in the bundled era-193 platform: the policy
 * accepts only unqualified local variables and parameters declared in the same class as the block.
 *
 * The control flow is therefore real (not degraded), so the converter still decides `val` vs `var`
 * correctly.
 */
object LocalCanBeFinal {

    /**
     * Builds the control flow for [body] using a policy that accepts variables declared in the same
     * class.
     *
     * @param body the code block to analyse.
     * @return the control flow, or `null` when the analysis is cancelled or the block has no
     * containing class.
     */
    @JvmStatic
    fun getControlFlow(body: PsiCodeBlock): ControlFlow? {
        val policy = object : ControlFlowPolicy {
            override fun getUsedVariable(refExpr: PsiReferenceExpression): PsiVariable? {
                if (refExpr.isQualified()) return null
                val refElement = refExpr.resolve()
                if (refElement !is PsiLocalVariable && refElement !is PsiParameter) return null
                val variable = refElement as PsiVariable
                return if (isVariableDeclaredInMethod(variable, body)) variable else null
            }

            override fun isParameterAccepted(psiParameter: PsiParameter): Boolean =
                isVariableDeclaredInMethod(psiParameter, body)

            override fun isLocalVariableAccepted(psiVariable: PsiLocalVariable): Boolean =
                isVariableDeclaredInMethod(psiVariable, body)
        }
        return try {
            ControlFlowFactory.getInstance(body.project).getControlFlow(body, policy, false)
        } catch (e: AnalysisCanceledException) {
            null
        }
    }

    /**
     * Returns whether [variable] and [body] belong to the same class.
     *
     * Locals from an enclosing method body are intentionally not accepted: the inspector only
     * handles variables whose whole lifetime is visible in this block.
     */
    private fun isVariableDeclaredInMethod(variable: PsiVariable, body: PsiCodeBlock): Boolean =
        PsiTreeUtil.getParentOfType(variable, PsiClass::class.java) ==
                PsiTreeUtil.getParentOfType(body, PsiClass::class.java)
}
