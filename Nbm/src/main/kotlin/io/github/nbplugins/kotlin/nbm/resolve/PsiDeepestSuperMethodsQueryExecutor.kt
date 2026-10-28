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
        for (superMethod in PsiSuperMethodImplUtil.findSuperMethods(method, false)) {
            if (PsiSuperMethodImplUtil.findSuperMethods(superMethod, false).isNotEmpty()) continue
            if (!consumer.process(superMethod)) return false
        }
        return true
    }
}
