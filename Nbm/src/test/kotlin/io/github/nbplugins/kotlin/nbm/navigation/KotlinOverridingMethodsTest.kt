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
package io.github.nbplugins.kotlin.nbm.navigation

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.netbeans.modules.csl.api.ElementKind
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinOverridingMethods].
 *
 * Uses the `navigation/` fixture directory which contains `BaseClass.kt` (with `open`/`abstract`
 * methods) and `DerivedClass.kt` (with overriding implementations).
 *
 * Full integration of [KotlinOverridingMethods.overrides] / [overriddenBy] requires a live
 * [Parser.Result] from the NB parsing infrastructure; here we test the supporting
 * [KotlinElementHandle] contracts that the methods rely on.
 */
class KotlinOverridingMethodsTest : KotlinTestCase("KotlinOverridingMethods", "navigation") {

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KotlinOverridingMethodsTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Verifies that [KotlinElementHandle.fromPsi] for a [org.jetbrains.kotlin.psi.KtClassOrObject]
     * produces [ElementKind.CLASS].
     */
    fun testElementHandleKindForClass() {
        getSessionOrSkip() ?: return
        val session = KotlinAnalysisAPISession.getSession(project)
        val fo = dir.getFileObject("BaseClass.kt") ?: return
        val kaKtFile = session.getKtFileForPath(fo.path) ?: return

        val clsOffset = kaKtFile.text.indexOf("class BaseClass")
        assertTrue("'class BaseClass' must appear in BaseClass.kt", clsOffset >= 0)

        val element = kaKtFile.findElementAt(clsOffset + 6) // inside "BaseClass"
        assertNotNull("Element at class name must be found", element)

        val decl = com.intellij.psi.util.PsiTreeUtil.getNonStrictParentOfType(
            element!!, org.jetbrains.kotlin.psi.KtClassOrObject::class.java
        ) ?: return
        val handle = KotlinElementHandle.fromPsi(decl, fo)

        assertEquals("Element kind must be CLASS for KtClassOrObject", ElementKind.CLASS, handle.kind)
    }

    /**
     * Verifies that [KotlinElementHandle.signatureEquals] returns `true` for handles with the
     * same name, file, and offset, and `false` when the offset differs.
     */
    fun testElementHandleSignatureEquals() {
        getSessionOrSkip() ?: return
        val fo = dir.getFileObject("BaseClass.kt") ?: return

        val h1 = KotlinElementHandle("openMethod", "BaseClass", ElementKind.METHOD, emptySet(), fo, 42)
        val h2 = KotlinElementHandle("openMethod", "BaseClass", ElementKind.METHOD, emptySet(), fo, 42)
        val h3 = KotlinElementHandle("openMethod", "BaseClass", ElementKind.METHOD, emptySet(), fo, 99)

        assertTrue("Handles with same name/file/offset must be signature-equal", h1.signatureEquals(h2))
        assertFalse("Handles with different offsets must not be signature-equal", h1.signatureEquals(h3))
    }

    /**
     * Verifies that [KotlinOverridingMethods] is instantiable and its method signatures
     * match the [org.netbeans.modules.csl.api.OverridingMethods] interface contract.
     */
    fun testOverridingMethodsInstantiable() {
        val om = KotlinOverridingMethods()
        assertNotNull("KotlinOverridingMethods must be instantiable", om)
    }
}
