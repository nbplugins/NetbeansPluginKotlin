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
package io.github.nbplugins.kotlin.nbm.resolve.providers

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinCompositeDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinFileBasedDeclarationProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.psi.KtFile

/**
 * A **live** [KotlinDeclarationProviderFactory] for the project's source module.
 *
 * The default `KotlinStandaloneDeclarationProviderFactory` builds a *frozen* declaration
 * index from the `KtFile` PSI instances present at session-creation time. After
 * [io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.updateFileContent]
 * transplants a fresh PSI tree into a `KtFile` (to reflect live editor edits), that frozen
 * index still points at the *old* `KtClass` instances. K2 then builds FIR from the *new*
 * instances and `FirElementFinder.findDeclaration` compares `firElement.psi == declaration`
 * by identity — the mismatch surfaces as
 * "Classifier was found in KtFile but was not found in FirFile".
 *
 * This factory avoids the frozen index by returning a [KotlinCompositeDeclarationProvider]
 * over per-file [KotlinFileBasedDeclarationProvider]s, each of which re-walks
 * `KtFile.declarations` on every query. The provider therefore always reflects the current
 * PSI, so the declaration view stays consistent with the FIR built from the same instances.
 *
 * For scopes that contain none of the [sourceKtFiles] (e.g. library or builtins scopes),
 * the call is forwarded to [delegate] — the original standalone factory — so binary and
 * builtins declaration lookups keep their existing behavior.
 *
 * @param sourceKtFiles the source module's `KtFile`s (the same instances whose PSI is
 *   transplanted on edit)
 * @param delegate the original standalone factory, used as a fallback for non-source scopes
 */
class LiveKotlinDeclarationProviderFactory(
    private val sourceKtFiles: Collection<KtFile>,
    private val delegate: KotlinDeclarationProviderFactory,
) : KotlinDeclarationProviderFactory {

    /**
     * Creates a declaration provider for [scope].
     *
     * If any [sourceKtFiles] fall within [scope], returns a composite of live per-file
     * providers for exactly those files. Otherwise forwards to [delegate].
     *
     * @param scope the search scope the provider must cover
     * @param contextualModule the module requesting the provider, forwarded to [delegate]
     * @return a [KotlinDeclarationProvider] that reflects the current PSI of in-scope files
     */
    override fun createDeclarationProvider(
        scope: GlobalSearchScope,
        contextualModule: KaModule?,
    ): KotlinDeclarationProvider {
        val inScope = sourceKtFiles.filter { file ->
            file.virtualFile?.let(scope::contains) == true
        }
        if (inScope.isEmpty()) {
            return delegate.createDeclarationProvider(scope, contextualModule)
        }
        return KotlinCompositeDeclarationProvider.create(
            inScope.map { KotlinFileBasedDeclarationProvider(it) }
        )
    }
}
