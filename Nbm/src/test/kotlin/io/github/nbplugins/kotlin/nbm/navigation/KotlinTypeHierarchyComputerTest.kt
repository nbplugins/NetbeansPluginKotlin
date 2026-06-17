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

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.psi.KtClassOrObject
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinTypeHierarchyComputer].
 *
 * Uses the existing `navigation/` fixture directory:
 * - `BaseClass.kt`: contains `BaseClass` (open), `AbstractBase` (abstract), `NavInterface`
 * - `DerivedClass.kt`: contains `DerivedClass : BaseClass`, `ConcreteImpl : AbstractBase`,
 *   `InterfaceImpl : NavInterface`
 *
 * Supertypes go upward (from derived toward base). Subtypes go downward (from base toward derived).
 */
class KotlinTypeHierarchyComputerTest : KotlinTestCase("KotlinTypeHierarchyComputer", "navigation") {

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KotlinTypeHierarchyComputerTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    private fun findClassDecl(kaFileText: String, className: String, session: KotlinAnalysisAPISession, fo: org.openide.filesystems.FileObject): KtClassOrObject? {
        val kaKtFile = session.getKtFileForPath(fo.path) ?: return null
        val offset = kaFileText.indexOf("class $className").takeIf { it >= 0 }
            ?: kaFileText.indexOf("interface $className").takeIf { it >= 0 }
            ?: return null
        val element = kaKtFile.findElementAt(offset + 6) ?: return null
        return PsiTreeUtil.getNonStrictParentOfType(element, KtClassOrObject::class.java)
    }

    /**
     * Verifies that [KotlinTypeHierarchyComputer.computeSupertypes] for `DerivedClass`
     * returns at least one node whose name is `BaseClass`.
     */
    fun testComputeSupertypesForDerivedClass() {
        val session = getSessionOrSkip() ?: return
        val derivedFo = dir.getFileObject("DerivedClass.kt") ?: return
        val kaKtFile = session.getKtFileForPath(derivedFo.path) ?: return

        val derivedDecl = findClassDecl(kaKtFile.text, "DerivedClass", session, derivedFo)
        assertNotNull("DerivedClass declaration must be found", derivedDecl)

        val computer = KotlinTypeHierarchyComputer()
        val supertypes = computer.computeSupertypes(derivedDecl!!, session)

        val names = supertypes.map { it.name }
        assertTrue(
            "Supertypes of DerivedClass must include BaseClass, got: $names",
            names.contains("BaseClass")
        )
    }

    /**
     * Verifies that [KotlinTypeHierarchyComputer.computeSubtypes] for `BaseClass`
     * returns at least one node whose name is `DerivedClass`.
     */
    fun testComputeSubtypesForBaseClass() {
        val session = getSessionOrSkip() ?: return
        val baseFo = dir.getFileObject("BaseClass.kt") ?: return
        val kaKtFile = session.getKtFileForPath(baseFo.path) ?: return

        val baseDecl = findClassDecl(kaKtFile.text, "BaseClass", session, baseFo)
        assertNotNull("BaseClass declaration must be found", baseDecl)

        val computer = KotlinTypeHierarchyComputer()
        val subtypes = computer.computeSubtypes(baseDecl!!, project, session)

        val names = subtypes.map { it.name }
        assertTrue(
            "Subtypes of BaseClass must include DerivedClass, got: $names",
            names.contains("DerivedClass")
        )
    }

    /**
     * Verifies that [KotlinTypeHierarchyComputer.computeSubtypes] for `NavInterface`
     * returns at least one node whose name is `InterfaceImpl`.
     */
    fun testComputeSubtypesForInterface() {
        val session = getSessionOrSkip() ?: return
        val baseFo = dir.getFileObject("BaseClass.kt") ?: return
        val kaKtFile = session.getKtFileForPath(baseFo.path) ?: return

        val ifaceDecl = findClassDecl(kaKtFile.text, "NavInterface", session, baseFo)
        assertNotNull("NavInterface declaration must be found", ifaceDecl)

        val computer = KotlinTypeHierarchyComputer()
        val subtypes = computer.computeSubtypes(ifaceDecl!!, project, session)

        val names = subtypes.map { it.name }
        assertTrue(
            "Subtypes of NavInterface must include InterfaceImpl, got: $names",
            names.contains("InterfaceImpl")
        )
    }

    /**
     * Verifies that [KotlinTypeHierarchyNode.kind] for a plain class is [HierarchyKind.CLASS]
     * and for an interface is [HierarchyKind.INTERFACE].
     */
    fun testNodeKindClassification() {
        val session = getSessionOrSkip() ?: return
        val baseFo = dir.getFileObject("BaseClass.kt") ?: return
        val kaKtFile = session.getKtFileForPath(baseFo.path) ?: return

        val classDecl = findClassDecl(kaKtFile.text, "BaseClass", session, baseFo)
        val ifaceDecl = findClassDecl(kaKtFile.text, "NavInterface", session, baseFo)

        assertNotNull("BaseClass must be found", classDecl)
        assertNotNull("NavInterface must be found", ifaceDecl)

        val classNode = KotlinTypeHierarchyNode.fromPsi(classDecl!!, baseFo)
        val ifaceNode = KotlinTypeHierarchyNode.fromPsi(ifaceDecl!!, baseFo)

        assertEquals("BaseClass node kind must be CLASS", HierarchyKind.CLASS, classNode.kind)
        assertEquals("NavInterface node kind must be INTERFACE", HierarchyKind.INTERFACE, ifaceNode.kind)
    }
}
