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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

/**
 * Answers [com.intellij.psi.search.searches.DeepestSuperMethodsSearch] from PSI alone.
 *
 * In the IDE the search is fed by the `com.intellij.deepestSuperMethodsSearch` extension point
 * declared in `java-impl`'s `plugin.xml`. A standalone K2 session loads no plugin descriptors, so
 * the point is missing and every `PsiMethod.findDeepestSuperMethods()` call throws
 * `IllegalArgumentException: Missing extension point`.
 *
 * The real executor uses the Java indexes. This one walks the resolved super-method chain instead
 * (`PsiSuperMethodImplUtil.findSuperMethods`, which is PSI/`JavaPsiFacade` based and needs no
 * indexes) and reports the methods of that chain which have no super methods of their own — the
 * same "most general declaration" semantics. It is what maps `equals`, `hashCode` and `toString`
 * overrides to their `java.lang.Object` declarations during Java → Kotlin conversion.
 *
 * Registered on the application extension area in
 * [KotlinAnalysisAPISession.installDeepestSuperMethodsSearch].
 */
class PsiDeepestSuperMethodsQueryExecutor : QueryExecutor<PsiMethod, PsiMethod> {

    /**
     * Reports the deepest super methods of [method] to [consumer].
     *
     * @param method the method whose super methods are requested
     * @param consumer receives every super method that has no super methods of its own
     * @return `true` when all reported methods were processed, `false` when [consumer] stopped
     */
    override fun execute(method: PsiMethod, consumer: Processor<in PsiMethod>): Boolean {
        val roots = LinkedHashSet<PsiMethod>()
        collectRoots(method, roots, HashSet())
        for (root in roots) {
            if (!consumer.process(root)) return false
        }
        return true
    }

    /**
     * Collects the super-method chain roots of [method] into [roots].
     *
     * `findSuperMethods` reports the nearest declaration in each supertype branch, not the whole
     * chain: with `B.equals` overriding `A.equals`, which overrides `Object.equals`, it returns
     * only `A.equals`. A declaration that still overrides something must therefore be followed up
     * instead of being discarded, or no root is found at all.
     *
     * @param method method whose ancestors are walked
     * @param roots receives every reached declaration that overrides nothing
     * @param visited guards against repeated and cyclic branches (diamond inheritance)
     */
    private fun collectRoots(
        method: PsiMethod,
        roots: MutableSet<PsiMethod>,
        visited: MutableSet<PsiMethod>,
    ) {
        for (superMethod in PsiSuperMethodImplUtil.findSuperMethods(method, false)) {
            if (!visited.add(superMethod)) continue
            if (PsiSuperMethodImplUtil.findSuperMethods(superMethod, false).isEmpty()) {
                roots += superMethod
            } else {
                collectRoots(superMethod, roots, visited)
            }
        }
    }
}
