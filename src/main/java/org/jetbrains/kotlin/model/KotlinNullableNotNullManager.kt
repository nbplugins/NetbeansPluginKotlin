/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.model

import com.intellij.codeInsight.NullableNotNullManagerBase
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifierListOwner
import org.netbeans.api.project.Project as NBProject

class KotlinNullableNotNullManager(
    private val nbProject: NBProject,
    intellijProject: Project
) : NullableNotNullManagerBase(intellijProject) {

    private val myNotNulls = mutableListOf("NotNull")
    private val myNullables = mutableListOf("Nullable")
    private var myDefaultNotNull = "NotNull"
    private var myDefaultNullable = "Nullable"

    override fun setNotNulls(vararg annotations: String) {
        myNotNulls.clear(); myNotNulls.addAll(annotations.toList())
    }
    override fun setNullables(vararg annotations: String) {
        myNullables.clear(); myNullables.addAll(annotations.toList())
    }
    override fun getDefaultNullable() = myDefaultNullable
    override fun setDefaultNullable(defaultNullable: String) { myDefaultNullable = defaultNullable }
    override fun getDefaultNotNull() = myDefaultNotNull
    override fun setDefaultNotNull(defaultNotNull: String) { myDefaultNotNull = defaultNotNull }
    override fun getNullables(): List<String> = myNullables
    override fun getNotNulls(): List<String> = myNotNulls
    override fun getInstrumentedNotNulls(): List<String> = emptyList()
    override fun setInstrumentedNotNulls(annotations: List<String>) {}
    override fun hasHardcodedContracts(element: PsiElement) = false

    override fun isNotNull(owner: PsiModifierListOwner, checkBases: Boolean): Boolean {
        val notNullAnnotations = myNotNulls.toSet()
        return owner.modifierList?.annotations?.any { annotation ->
            annotation.qualifiedName in notNullAnnotations
        } ?: false
    }

    override fun isNullable(owner: PsiModifierListOwner, checkBases: Boolean) = !isNotNull(owner, checkBases)
}
