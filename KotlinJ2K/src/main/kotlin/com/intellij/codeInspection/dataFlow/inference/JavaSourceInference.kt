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
package com.intellij.codeInspection.dataFlow.inference

import com.intellij.codeInsight.Nullability
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType

/**
 * Standalone replacement for IDEA's `JavaSourceInference`.
 *
 * IDEA infers Java parameter nullability by analysing all assignments to the parameter inside the
 * method body (the `java-analysis-impl` "inference" pass). That pass is not bundled, so this stub
 * falls back to the **declared annotations**: an annotated parameter keeps its annotation-based
 * nullability, everything else is [Nullability.UNKNOWN].
 *
 * The J2K engine uses the result only to decide whether a parameter type gets a `?`; `UNKNOWN`
 * keeps the Java default (non-null), which matches the old K1 converter's output.
 */
object JavaSourceInference {

    /**
     * Infers the nullability of [parameter].
     *
     * @param parameter the Java method parameter to inspect.
     * @return [Nullability.UNKNOWN] for primitive parameters, the annotated nullability when the
     * parameter is annotated, otherwise [Nullability.UNKNOWN].
     */
    @JvmStatic
    fun inferNullability(parameter: PsiParameter): Nullability {
        if (parameter.type is PsiPrimitiveType) return Nullability.UNKNOWN
        return NullableNotNullManager.getNullability(parameter)
    }
}
