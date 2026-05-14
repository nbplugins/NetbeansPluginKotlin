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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.netbeans.modules.csl.api.ElementKind
import utils.KotlinTestCase

/**
 * Unit tests for [KaCompletionProposalFactory].
 *
 * Uses real K2 symbols obtained via [analyze] from [KotlinAnalysisAPISession] source files.
 */
class KaCompletionProposalFactoryTest : KotlinTestCase("KaCompletionProposalFactory test", "completion") {

    private fun firstKtFile(): KtFile? =
        KotlinAnalysisAPISession.getSession(project).session
            .modulesWithFiles.values.flatten().filterIsInstance<KtFile>().firstOrNull()

    /**
     * Verifies that [KaCompletionProposalFactory.toProposal] maps a [KaFunctionSymbol]
     * to a proposal with [ElementKind.METHOD] and the correct identifier name.
     *
     * Skips when no top-level function is found in the K2 session source files.
     */
    fun testFunctionSymbol_mapsToMethodKind() {
        val ktFile = firstKtFile() ?: return
        val funcDecl = (ktFile as? KtFile)?.declarations?.filterIsInstance<KtNamedFunction>()?.firstOrNull()
            ?: run {
                // Try any file in session
                KotlinAnalysisAPISession.getSession(project).session
                    .modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
                    .flatMap { it.declarations.filterIsInstance<KtNamedFunction>() }
                    .firstOrNull()
            } ?: return  // No function found; skip test

        analyze(ktFile) {
            val sym = funcDecl.symbol as? KaFunctionSymbol ?: return@analyze
            val proposal = KaCompletionProposalFactory.toProposal(sym, anchorOffset = 0, prefix = "")
            assertNotNull("toProposal must not return null for a named function", proposal)
            assertEquals(ElementKind.METHOD, proposal!!.kind)
            assertEquals(funcDecl.name, proposal.name)
        }
    }

    /**
     * Verifies that [KaCompletionProposalFactory.toProposal] maps a [KaClassLikeSymbol]
     * to a proposal with [ElementKind.CLASS].
     *
     * Skips when no top-level class is found in the K2 session source files.
     */
    fun testClassSymbol_mapsToClassKind() {
        val ktFile = firstKtFile() ?: return
        val classDecl = KotlinAnalysisAPISession.getSession(project).session
            .modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
            .flatMap { it.declarations.filterIsInstance<KtClassOrObject>() }
            .firstOrNull() ?: return  // No class found; skip test

        val ownerFile = classDecl.containingKtFile
        analyze(ownerFile) {
            val sym = classDecl.symbol as? KaClassLikeSymbol ?: return@analyze
            val proposal = KaCompletionProposalFactory.toProposal(sym, anchorOffset = 0, prefix = "")
            assertNotNull("toProposal must not return null for a named class", proposal)
            assertEquals(ElementKind.CLASS, proposal!!.kind)
        }
    }

    /**
     * Verifies that [KaCompletionProposalFactory.toProposal] maps a [KaPropertySymbol]
     * to a proposal with [ElementKind.FIELD].
     *
     * Skips when no top-level property is found in the K2 session source files.
     */
    fun testPropertySymbol_mapsToFieldKind() {
        val ktFile = firstKtFile() ?: return
        val propDecl = KotlinAnalysisAPISession.getSession(project).session
            .modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
            .flatMap { it.declarations.filterIsInstance<KtProperty>() }
            .firstOrNull() ?: return  // No property found; skip test

        val ownerFile = propDecl.containingKtFile
        analyze(ownerFile) {
            val sym = propDecl.symbol as? KaPropertySymbol ?: return@analyze
            val proposal = KaCompletionProposalFactory.toProposal(sym, anchorOffset = 0, prefix = "")
            assertNotNull("toProposal must not return null for a named property", proposal)
            assertEquals(ElementKind.FIELD, proposal!!.kind)
        }
    }
}
