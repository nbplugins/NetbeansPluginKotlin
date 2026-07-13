/*******************************************************************************
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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import com.intellij.psi.util.PsiTreeUtil

/**
 * Real (project-wide) implementation of [KotlinChangeSignatureUsageSearchService], registered as an
 * application service by [KotlinAnalysisAPISession.registerStandaloneServices].
 *
 * IDEA's ported Change Signature engine expects `ReferencesSearch`/`MethodReferencesSearch`, backed
 * by a project index this plugin's standalone container never builds. This reuses the same
 * whole-project scan-and-resolve strategy already used by Find Usages (E7)'s
 * [io.github.nbplugins.kotlin.nbm.navigation.KaFindUsagesComputer] and Move Declaration (E9.7)'s
 * [KotlinMoveUsageSearchServiceImpl]: iterate every `KtFile` the session that owns [element] knows
 * about and resolve each simple-name reference.
 *
 * [KtSimpleNameExpression] references cover plain function/constructor calls (including a
 * primary-constructor-style supertype call, `class Derived : Base(args)` — `Base`'s callee is a
 * plain simple-name reference), parameter-name references inside the body, and callable references
 * (`::foo` — the name part of a `KtCallableReferenceExpression` is itself a [KtSimpleNameExpression]),
 * so a single whole-project scan of simple-name references (below) covers all of them. Destructuring
 * and enum-entry-without-super-call usages don't need reference search at all — the ported engine
 * finds them structurally (declaration shape, not references). [findOverridings] and
 * [findConstructorDelegationCallers] are two further, separate whole-project scans for usage kinds
 * plain reference search can't find: declarations that override [element] without literally
 * referencing it, and `this(...)`/`super(...)` constructor-delegation calls (whose callee,
 * `KtConstructorDelegationReferenceExpression`, is a `KtReferenceExpression` but *not* a
 * [KtSimpleNameExpression], so the general scan's visitor never visits it).
 */
class KotlinChangeSignatureUsageSearchServiceImpl : KotlinChangeSignatureUsageSearchService {
    override fun findUsages(element: PsiElement): List<PsiReference> {
        val session = KotlinAnalysisAPISession.forProject(element.project) ?: return emptyList()
        val references = mutableListOf<PsiReference>()
        for (kaFile in session.fileMap.values) {
            val ktFile = session.getKtFileForPath(kaFile.path) ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            super.visitSimpleNameExpression(expression)
                            // Same import-directive exclusion as KotlinMoveUsageSearchServiceImpl:
                            // no live-document sync to keep the import list consistent standalone.
                            if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                            val reference = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
                            val resolved = runCatching { reference.resolveToSymbol()?.psi }.getOrNull() ?: return
                            if (resolved == element || isOverrideRelated(resolved, element)) {
                                references += reference
                            }
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinChangeSignatureUsageSearchServiceImpl: search failed in ${ktFile.name}", e
                )
            }
        }
        return references
    }

    /**
     * Whether [resolved] and [target] are two declarations in the same override chain (one
     * directly or transitively overrides the other), rather than the exact same declaration.
     *
     * A call site's static receiver type decides which override K2 resolves it to: `val b: B =
     * B(); b.ff(...)` resolves to `B`'s override of `ff`, not `Base`'s declaration — even when
     * Change Signature was invoked on `Base.ff`. Real IDEA's `ReferencesSearch` already accounts
     * for virtual dispatch when searching for usages of an overridable member; plain PSI-identity
     * comparison (`resolved == element`) here does not, so a call site through *any* member of the
     * override chain must also count as a usage — otherwise adding a parameter propagates to every
     * override's own declaration (via [findOverridings]) but never reaches the call sites that
     * happen to resolve through one of those overrides.
     */
    private fun KaSession.isOverrideRelated(resolved: PsiElement, target: PsiElement): Boolean {
        if (resolved !is KtCallableDeclaration || target !is KtCallableDeclaration) return false
        val resolvedSymbol = runCatching { resolved.symbol as? KaCallableSymbol }.getOrNull() ?: return false
        val targetSymbol = runCatching { target.symbol as? KaCallableSymbol }.getOrNull() ?: return false
        return runCatching {
            resolvedSymbol.allOverriddenSymbols.any { it.psi == target } ||
                targetSymbol.allOverriddenSymbols.any { it.psi == resolved }
        }.getOrDefault(false)
    }

    override fun findOverridings(element: PsiElement): List<PsiElement> {
        val session = KotlinAnalysisAPISession.forProject(element.project) ?: return emptyList()
        val overridings = mutableListOf<PsiElement>()
        for (kaFile in session.fileMap.values) {
            val ktFile = session.getKtFileForPath(kaFile.path) ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                            super.visitNamedDeclaration(declaration)
                            if (declaration == element) return
                            val symbol = runCatching { declaration.symbol as? KaCallableSymbol }.getOrNull() ?: return
                            val overridesTarget = runCatching {
                                symbol.allOverriddenSymbols.any { it.psi == element }
                            }.getOrNull() ?: return
                            if (overridesTarget) overridings += declaration
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinChangeSignatureUsageSearchServiceImpl.findOverridings: search failed in ${ktFile.name}", e
                )
            }
        }
        return overridings
    }

    /**
     * `KtSuperTypeCallEntry` (`class Derived : Base(args)`) is *not* handled here — its callee
     * (`Base`) is a plain [KtSimpleNameExpression], already found by [findUsages]'s general scan.
     * Only [KtConstructorDelegationCall] (`this(...)`/`super(...)` inside another constructor's own
     * delegation clause) needs this dedicated scan — see class doc.
     */
    override fun findConstructorDelegationCallers(element: PsiElement): List<PsiElement> {
        val session = KotlinAnalysisAPISession.forProject(element.project) ?: return emptyList()
        val callers = mutableListOf<PsiElement>()
        for (kaFile in session.fileMap.values) {
            val ktFile = session.getKtFileForPath(kaFile.path) ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitConstructorDelegationCall(call: KtConstructorDelegationCall) {
                            super.visitConstructorDelegationCall(call)
                            val callee = call.calleeExpression ?: return
                            val resolved = runCatching {
                                callee.references.filterIsInstance<KtReference>().firstOrNull()?.resolveToSymbol()?.psi
                            }.getOrNull()
                            if (resolved == element) callers += call
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinChangeSignatureUsageSearchServiceImpl.findConstructorDelegationCallers: search failed in ${ktFile.name}", e
                )
            }
        }
        return callers
    }
}
