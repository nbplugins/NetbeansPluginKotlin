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
package io.github.nbplugins.kotlin.nbm.refactoring

import com.intellij.psi.PsiElement
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.idea.searching.inheritors.StandaloneInheritorSearch
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Build-scoped K2 replacement for IntelliJ's indexed inheritor and override searches.
 *
 * IDEA Push Members Down calls its normal hierarchy search APIs. This implementation scans every
 * writable source file owned by the active build-wide [KotlinAnalysisAPISession], so sibling Maven
 * and Gradle modules participate without changing the copied IDEA processor.
 *
 * @param session owning build-wide K2 analysis session.
 */
class KotlinStandaloneInheritorSearch(
    private val session: KotlinAnalysisAPISession,
) : StandaloneInheritorSearch {
    /** Finds direct or transitive Kotlin inheritors of [element] in this build session. */
    override fun search(element: PsiElement, deep: Boolean): Sequence<PsiElement> {
        val source = element as? KtClassOrObject ?: return emptySequence()
        val direct = collectDirectInheritors(source)
        if (!deep) return direct.asSequence()
        val result = linkedSetOf<PsiElement>()
        val pending = ArrayDeque(direct)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!result.add(next)) continue
            pending.addAll(collectDirectInheritors(next))
        }
        return result.asSequence()
    }

    /** Finds Kotlin declarations that override [element] in this build session. */
    override fun searchOverriders(element: PsiElement, deep: Boolean): Sequence<PsiElement> {
        val target = element as? KtNamedDeclaration ?: return emptySequence()
        val matches = linkedSetOf<PsiElement>()
        for (file in session.session.modulesWithFiles.values.flatten()) {
            val ktFile = file as? org.jetbrains.kotlin.psi.KtFile ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                            super.visitNamedDeclaration(declaration)
                            if (declaration == target) return
                            val symbol = declaration.symbol as? KaCallableSymbol ?: return
                            if (symbol.allOverriddenSymbols.any { it.psi == target }) matches += declaration
                        }
                    })
                }
            }.onFailure { error ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinStandaloneInheritorSearch: override scan failed in ${ktFile.name}", error,
                )
            }
        }
        if (!deep) return matches.asSequence()
        return matches.asSequence()
    }

    /** Scans the session for classes whose declared K2 supertypes contain [source]. */
    private fun collectDirectInheritors(source: KtClassOrObject): List<KtClassOrObject> {
        val matches = linkedSetOf<KtClassOrObject>()
        for (file in session.session.modulesWithFiles.values.flatten()) {
            val ktFile = file as? org.jetbrains.kotlin.psi.KtFile ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitClassOrObject(candidate: KtClassOrObject) {
                            super.visitClassOrObject(candidate)
                            if (candidate == source) return
                            val symbol = candidate.symbol as? KaClassSymbol ?: return
                            if (symbol.superTypes.any { it.symbol?.psi == source }) matches += candidate
                        }
                    })
                }
            }.onFailure { error ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinStandaloneInheritorSearch: inheritor scan failed in ${ktFile.name}", error,
                )
            }
        }
        return matches.toList()
    }
}
