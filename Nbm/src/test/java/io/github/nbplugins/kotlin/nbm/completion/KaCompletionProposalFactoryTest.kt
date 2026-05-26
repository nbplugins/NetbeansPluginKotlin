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
package io.github.nbplugins.kotlin.nbm.completion

import org.netbeans.modules.csl.api.ElementKind
import utils.KotlinTestCase

/**
 * Unit tests for [KaCompletionProposalFactory].
 *
 * The factory is a thin mapper from [KaCompletionItem] to [KaCompletionProposal].
 * Tests construct [KaCompletionItem] values directly — no K2 session needed.
 */
class KaCompletionProposalFactoryTest : KotlinTestCase("KaCompletionProposalFactory test", "completion") {

    /**
     * Verifies that a [KaCompletionItem] with [ElementKind.METHOD] maps to a proposal
     * with kind METHOD and the correct name.
     */
    fun testFunctionItem_mapsToMethodKind() {
        val item = KaCompletionItem("myFun", ElementKind.METHOD, isFunctionLike = true,
                                   signature = "(x: Int): Boolean", icon = null)
        val proposal = KaCompletionProposalFactory.toProposal(item, anchorOffset = 5, prefix = "my")
        assertEquals(ElementKind.METHOD, proposal.kind)
        assertEquals("myFun", proposal.name)
        assertEquals(5, proposal.anchorOffset)
    }

    /**
     * Verifies that a [KaCompletionItem] with [ElementKind.CLASS] maps to a proposal
     * with kind CLASS.
     */
    fun testClassItem_mapsToClassKind() {
        val item = KaCompletionItem("MyClass", ElementKind.CLASS, isFunctionLike = false,
                                   signature = "", icon = null)
        val proposal = KaCompletionProposalFactory.toProposal(item, anchorOffset = 0, prefix = "")
        assertEquals(ElementKind.CLASS, proposal.kind)
        assertEquals("MyClass", proposal.name)
    }

    /**
     * Verifies that a [KaCompletionItem] with [ElementKind.FIELD] maps to a proposal
     * with kind FIELD and that the signature is forwarded to [KaCompletionProposal].
     */
    fun testPropertyItem_mapsToFieldKind() {
        val item = KaCompletionItem("myProp", ElementKind.FIELD, isFunctionLike = false,
                                   signature = ": String", icon = null)
        val proposal = KaCompletionProposalFactory.toProposal(item, anchorOffset = 0, prefix = "")
        assertEquals(ElementKind.FIELD, proposal.kind)
        assertEquals("myProp", proposal.name)
    }
}
