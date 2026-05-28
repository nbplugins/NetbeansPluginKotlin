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
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [LiveKotlinDeclarationProviderFactory].
 *
 * Verifies the single public method [LiveKotlinDeclarationProviderFactory.createDeclarationProvider]:
 * that it (a) covers in-scope source files, (b) reflects live PSI after
 * [KotlinAnalysisAPISession.updateFileContent], and (c) delegates for scopes containing no
 * source files. The live-PSI behavior is the whole reason this factory replaces the frozen
 * standalone factory.
 */
class LiveKotlinDeclarationProviderFactoryTest : KotlinTestCase("K2 live declaration provider", "diagnostics") {

    private val base1ClassId = ClassId(FqName("diagnostics"), Name.identifier("Base1"))

    override fun tearDown() {
        KotlinAnalysisAPISession.disposeAll()
        super.tearDown()
    }

    /**
     * The session must register a [LiveKotlinDeclarationProviderFactory] in place of the
     * default standalone factory.
     */
    fun testFactoryIsRegistered() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val factory = KotlinDeclarationProviderFactory.getInstance(wrapper.session.project)
        assertTrue(
            "Expected LiveKotlinDeclarationProviderFactory but was ${factory::class.java.name}",
            factory is LiveKotlinDeclarationProviderFactory
        )
    }

    /**
     * `createDeclarationProvider` over an all-files scope must surface a class declared in
     * an in-scope source file.
     */
    fun testCreateDeclarationProvider_coversInScopeSourceFiles() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val k2Project = wrapper.session.project
        val factory = KotlinDeclarationProviderFactory.getInstance(k2Project)

        val provider = factory.createDeclarationProvider(GlobalSearchScope.allScope(k2Project), null)
        val base1 = provider.getClassLikeDeclarationByClassId(base1ClassId)
        assertNotNull("Provider must find 'diagnostics/Base1' from the source module", base1)
    }

    /**
     * Unknown class IDs must yield `null`.
     */
    fun testCreateDeclarationProvider_returnsNullForUnknownClassId() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val k2Project = wrapper.session.project
        val factory = KotlinDeclarationProviderFactory.getInstance(k2Project)

        val provider = factory.createDeclarationProvider(GlobalSearchScope.allScope(k2Project), null)
        val unknown = provider.getClassLikeDeclarationByClassId(
            ClassId(FqName("diagnostics"), Name.identifier("NoSuchClassXyz"))
        )
        assertNull("Unknown class ID must return null", unknown)
    }

    /**
     * The core regression check: after [KotlinAnalysisAPISession.updateFileContent] injects a
     * new class, the provider returns the **current** PSI instance — the same object found in
     * `ktFile.declarations`, not a stale snapshot.
     */
    fun testCreateDeclarationProvider_reflectsLivePsiAfterUpdate() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val k2Project = wrapper.session.project
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull("checkTypeMismatch.kt must be in the source module", ktFile)
        val path = ktFile!!.virtualFile!!.path

        val injectedClassId = ClassId(FqName("diagnostics"), Name.identifier("InjectedClass"))
        wrapper.updateFileContent(path, ktFile.text + "\nclass InjectedClass\n")

        val factory = KotlinDeclarationProviderFactory.getInstance(k2Project)
        val provider = factory.createDeclarationProvider(GlobalSearchScope.allScope(k2Project), null)
        val fromProvider = provider.getClassLikeDeclarationByClassId(injectedClassId)
        assertNotNull("Provider must surface the freshly injected class", fromProvider)

        val fromPsi = ktFile.declarations.filterIsInstance<KtClass>().find { it.name == "InjectedClass" }
        assertNotNull("Injected class must appear in the reparsed PSI", fromPsi)
        assertSame(
            "Provider must return the current PSI instance, not a stale snapshot",
            fromPsi, fromProvider
        )
    }

    /**
     * A scope containing no source files must fall back to the delegate factory, returning a
     * provider that simply finds nothing for source class IDs (and does not throw).
     */
    fun testCreateDeclarationProvider_delegatesForEmptyScope() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val factory = KotlinDeclarationProviderFactory.getInstance(wrapper.session.project)

        val provider = factory.createDeclarationProvider(GlobalSearchScope.EMPTY_SCOPE, null)
        assertNull(
            "Empty scope must not surface source declarations",
            provider.getClassLikeDeclarationByClassId(base1ClassId)
        )
    }
}
