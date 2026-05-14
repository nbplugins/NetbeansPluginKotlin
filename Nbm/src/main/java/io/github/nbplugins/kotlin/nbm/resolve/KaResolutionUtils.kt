/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.resolve

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * K2 Analysis API equivalents of the K1 scope and reference utilities in
 * `org.jetbrains.kotlin.idea.util`.
 *
 * All functions use `context(KaSession)` and must be called inside an `analyze { }` block.
 * The `-Xcontext-receivers` compiler flag is required (enabled in `Nbm/pom.xml`).
 */
object KaResolutionUtils {

    /**
     * Returns the composite [KaScope] visible at the position of [element].
     *
     * This is the K2 equivalent of `getResolutionScope(bindingContext, resolutionFacade)`
     * from `org.jetbrains.kotlin.idea.util.scopeUtils`.
     *
     * @param element the PSI element whose enclosing scope is requested
     * @return the composite [KaScope] at the element's position
     */
    context(KaSession)
    fun getResolutionScope(element: KtElement): KaScope {
        val ctx = element.containingKtFile.scopeContext(element)
        return ctx.scopes.map { it.scope }.asCompositeScope()
    }

    /**
     * Resolves the owning callable of a `this` expression — i.e. the class or function
     * whose receiver the `this` keyword refers to.
     *
     * This is the K2 equivalent of `getThisReceiverOwner(bindingContext)` from
     * `org.jetbrains.kotlin.idea.util.extensionsUtils`.
     *
     * @param thisExpression the `this` PSI expression to resolve
     * @return the owning [KaSymbol], or `null` if unresolvable
     */
    context(KaSession)
    fun getThisReceiverOwner(thisExpression: KtThisExpression): KaSymbol? {
        val ktRef = thisExpression.instanceReference.references
            .filterIsInstance<KtReference>()
            .firstOrNull() ?: return null
        val symbol = ktRef.resolveToSymbol() ?: return null
        return if (symbol is KaReceiverParameterSymbol) symbol.owningCallableSymbol else symbol
    }
}
