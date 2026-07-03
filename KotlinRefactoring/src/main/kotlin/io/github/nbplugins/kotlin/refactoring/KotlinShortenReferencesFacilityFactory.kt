/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.components.ShortenOptions
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.idea.base.fir.codeInsight.SymbolBasedShortenReferencesFacility
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * Exposes the IDEA K2 `SymbolBasedShortenReferencesFacility` (declared `internal` in its
 * source file) to consumers outside the `KotlinRefactoring` module.
 *
 * The Nbm module registers an instance of this class as the application service implementation
 * for [ShortenReferencesFacility]; without it, IDEA's `InlinePostProcessor.shortenReferences`
 * throws `RuntimeException: Cannot find service` at the end of an inline refactoring.
 *
 * The two abstract methods clear the FIR-session caches (see [invalidateFirCaches]) before
 * delegating — needed when the element/range to shorten was just inserted by a live PSI mutation
 * in the same session (e.g. the real Extract Function generator, E9 Phase 2): our standalone
 * container has no PSI-change-listener-driven invalidation, so without this, resolving a
 * brand-new declaration for shortening throws `KotlinIllegalArgumentExceptionWithAttachments:
 * No fir element was found`. The default-overload methods on the interface forward to these two,
 * so every caller is covered.
 */
class KotlinSymbolBasedShortenReferencesFacility : ShortenReferencesFacility {
    private val delegate = SymbolBasedShortenReferencesFacility()

    override fun shorten(file: KtFile, range: TextRange, shortenOptions: ShortenOptions) {
        invalidateFirCaches(file.project)
        delegate.shorten(file, range, shortenOptions)
    }

    override fun shorten(element: KtElement, shortenOptions: ShortenOptions): PsiElement? {
        invalidateFirCaches(element.project)
        return delegate.shorten(element, shortenOptions)
    }

    /**
     * Clears the two FIR-session caches that may hold state built before [project]'s live PSI was
     * last mutated, so the next analysis access rebuilds them from the current PSI. Same two-step
     * clear `KotlinAnalysisAPISession.updateFileContent` (Nbm module) uses for the same reason — a
     * real IDE clears these via `LLFirSessionInvalidationService` listeners fired by PSI change
     * events, which standalone/`MockProject` mode does not have wired up. Each step is
     * independently best-effort: a failure in one must not skip the other.
     */
    @OptIn(
        org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals::class,
        org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    )
    private fun invalidateFirCaches(project: Project) {
        runCatching {
            org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache
                .getInstance(project)
                .storage.sourceCache.clear("KotlinSymbolBasedShortenReferencesFacility.shorten")
        }
        runCatching {
            org.jetbrains.kotlin.analysis.api.session.KaSessionProvider
                .getInstance(project)
                .clearCaches()
        }
    }
}
