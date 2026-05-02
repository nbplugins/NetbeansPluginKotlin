/*
 * Copyright 2010-2016 JetBrains s.r.o.
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
 */

package org.jetbrains.kotlin.idea.util

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

// Stub: shadow filtering skipped (complex call resolution not needed for NetBeans)
class ShadowedDeclarationsFilter(
        private val bindingContext: BindingContext,
        private val resolutionFacade: ResolutionFacade,
        private val context: PsiElement,
        private val explicitReceiverValue: ReceiverValue?
) {
    companion object {
        fun create(
                bindingContext: BindingContext,
                resolutionFacade: ResolutionFacade,
                context: PsiElement,
                callTypeAndReceiver: CallTypeAndReceiver<*, *>
        ): ShadowedDeclarationsFilter? = null
    }

    fun <TDescriptor : DeclarationDescriptor> filter(declarations: Collection<TDescriptor>): Collection<TDescriptor> = declarations

    fun <TDescriptor : DeclarationDescriptor> createNonImportedDeclarationsFilter(
            importedDeclarations: Collection<DeclarationDescriptor>
    ): ((Collection<TDescriptor>) -> Collection<TDescriptor>) = { it }
}
