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
package io.github.nbplugins.kotlin.nbm.refactoring

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.idea.search.KotlinSearchUsagesSupport
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Tests the standalone `KotlinSearchUsagesSupport.SearchUtils` bridge added for the J2K port.
 *
 * The bridge lives in the `KotlinRefactoring` module, which has no test source set of its own;
 * these tests run it against the same standalone K2 session the plugin uses.
 */
class KotlinSearchUsagesSupportTest : KotlinTestCase("KotlinSearchUsagesSupportTest", "searchusages") {

    /** Session over the fixture directory; created on first use. */
    private val session: KotlinAnalysisAPISession by lazy {
        KotlinAnalysisAPISession.createWithJars(
            moduleName = project.projectDirectory.name,
            binaryJars = emptyList(),
            sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
        )
    }

    /**
     * Returns the `foo` function declared in the given class of the fixture.
     *
     * @param className simple name of the declaring class
     * @return the function, or `null` when the fixture does not contain it
     */
    private fun functionIn(className: String): KtNamedFunction? {
        val fileObject = dir.getFileObject("deepest.kt") ?: return null
        val ktFile = session.getKtFileForPath(fileObject.path) ?: return null
        return PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)
            .firstOrNull { function ->
                function.name == "foo" && (function.parent as? KtClass)?.name == className
            }
    }

    /** The override in `Middle` reports the `Base` declaration as its direct super method. */
    fun testFindSuperMethodsNoWrappingReturnsDirectSupers() {
        val middleFoo = functionIn("Middle") ?: return
        val supers = KotlinSearchUsagesSupport.SearchUtils.findSuperMethodsNoWrapping(middleFoo)
        assertEquals("direct supers of Middle.foo", 1, supers.size)
        assertEquals("Base", ((supers[0] as KtNamedFunction).parent as? KtClass)?.name)
    }

    /** The override in `Leaf` reports only the most basic declaration, `Base.foo`. */
    fun testFindDeepestSuperMethodsNoWrappingReturnsBaseDeclaration() {
        val leafFoo = functionIn("Leaf") ?: return
        val deepest = KotlinSearchUsagesSupport.SearchUtils.findDeepestSuperMethodsNoWrapping(leafFoo)
        assertEquals("deepest super methods of Leaf.foo", 1, deepest.size)
        assertEquals("Base", ((deepest[0] as KtNamedFunction).parent as? KtClass)?.name)
    }
}
