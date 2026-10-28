/*
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
 */
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession

/**
 * [KaModuleProvider] backed by a single [StandaloneAnalysisAPISession].
 *
 * IDEA's default provider resolves a PSI element to its [KaModule] through the project/module
 * index and the IDE's module manager. A standalone session has neither: its files live in
 * [StandaloneAnalysisAPISession.modulesWithFiles] instead. This provider answers from that map, so
 * IDEA code compiled against the IDE provider (the J2K engine's
 * `Module.toKaSourceModuleWithElementSourceModuleKindOrProduction`, for example) works unchanged.
 *
 * Resolution order:
 * 1. the module that owns [PsiElement.getContainingFile] (identity comparison — the session's PSI
 *    nodes are unique per module);
 * 2. [useSiteModule], when the caller already resolved a module (e.g. from an enclosing
 *    `analyze {}` block);
 * 3. the session's source module — a standalone session has exactly one per NetBeans project,
 *    plus optional library and sibling-source modules.
 *
 * @param session the standalone session whose files are resolved.
 */
@OptIn(KaImplementationDetail::class)
class StandaloneKaModuleProvider(
    private val session: StandaloneAnalysisAPISession,
) : KaModuleProvider {

    /**
     * Returns the [KaModule] that [element] belongs to.
     *
     * @param element the PSI element whose module is requested.
     * @param useSiteModule the module already known to the caller, used when [element] is not
     * owned by any of the session's source files.
     * @return the owning module; never `null`.
     * @throws IllegalStateException when the session contains no modules at all — a state that
     * cannot occur for a session built by [KotlinAnalysisAPISession], where a source module is
     * always added.
     */
    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
        val containingFile = element.containingFile
        if (containingFile != null) {
            session.modulesWithFiles.entries
                .firstOrNull { (_, files) -> files.any { it === containingFile } }
                ?.let { return it.key }
        }
        useSiteModule?.let { return it }
        session.modulesWithFiles.keys.firstOrNull { it is KaSourceModule }?.let { return it }
        return session.modulesWithFiles.keys.firstOrNull()
            ?: error("StandaloneKaModuleProvider: the analysis session contains no modules")
    }
}
