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

import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.idea.base.projectStructure.toKaSourceModuleWithElementSourceModuleKindOrProduction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Tests [StandaloneKaModuleProvider], the [org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider]
 * replacement used in standalone K2 sessions.
 *
 * The provider's contract is three-step: the module owning the element's file, then the caller's
 * `useSiteModule`, then the session's source module. Each step gets a test; the fixture
 * `providerProbe.kt` makes the source module non-empty.
 */
class StandaloneKaModuleProviderTest : KotlinTestCase("StandaloneKaModuleProviderTest", "moduleprovider") {

    /** Session over the fixture directory; created on first use. */
    private val session: KotlinAnalysisAPISession by lazy {
        KotlinAnalysisAPISession.createWithJars(
            moduleName = project.projectDirectory.name,
            binaryJars = emptyList(),
            sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
        )
    }

    /** The provider under test, backed by [session]. */
    private val provider: StandaloneKaModuleProvider by lazy {
        StandaloneKaModuleProvider(session.session)
    }

    /**
     * Creates a Kotlin file that is not part of [session].
     *
     * @return a PSI file unknown to the session
     */
    private fun foreignFile() =
        KtPsiFactory(session.session.project).createFile("Foreign.kt", "class Foreign")

    /** A file from the session resolves to the module whose `modulesWithFiles` entry owns it. */
    fun testElementInSessionResolvesToItsOwnerModule() {
        val fileObject = dir.getFileObject("providerProbe.kt") ?: return
        val ktFile = session.getKtFileForPath(fileObject.path) ?: return
        val owner = session.session.modulesWithFiles.entries
            .first { (_, files) -> files.any { it === ktFile } }

        val module = provider.getModule(ktFile, null)

        assertTrue("owning module must be a source module", module is KaSourceModule)
        assertSame("module must be the one that owns the file", owner.key, module)
    }

    /** With no owner match and no `useSiteModule`, the provider falls back to the source module. */
    fun testForeignElementFallsBackToSourceModule() {
        val module = provider.getModule(foreignFile(), null)

        assertTrue("fallback module must be a source module", module is KaSourceModule)
        assertTrue(
            "fallback module must belong to the session",
            session.session.modulesWithFiles.keys.any { it === module },
        )
    }

    /**
     * `Module.toKaSourceModuleWithElementSourceModuleKindOrProduction` — the bridge the J2K
     * engine calls — resolves through this provider to the session's source module.
     */
    fun testModuleBridgeResolvesThroughProvider() {
        val fileObject = dir.getFileObject("providerProbe.kt") ?: return
        val ktFile = session.getKtFileForPath(fileObject.path) ?: return
        val module = com.intellij.openapi.module.SingletonModule.forProject(session.session.project)

        val sourceModule = module.toKaSourceModuleWithElementSourceModuleKindOrProduction(ktFile)

        assertNotNull("the bridge must return a source module", sourceModule)
        assertSame(provider.getModule(ktFile, null), sourceModule)
    }

    /** With no owner match, the caller-supplied `useSiteModule` wins over the fallback. */
    fun testUseSiteModuleIsReturnedForForeignElement() {
        val useSite = session.session.modulesWithFiles.keys.first()

        assertSame(useSite, provider.getModule(foreignFile(), useSite))
    }
}
