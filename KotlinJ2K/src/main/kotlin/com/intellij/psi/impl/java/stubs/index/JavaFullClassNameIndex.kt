/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package com.intellij.psi.impl.java.stubs.index

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope

/**
 * Standalone replacement for IDEA's `JavaFullClassNameIndex`.
 *
 * The real index is a stub-index lookup over the Java index, which the standalone session does not
 * build. This replacement delegates to [JavaPsiFacade.findClasses], which in the standalone session
 * resolves classes from the source files registered in the analysis session and from the JDK
 * dependency. That is exactly the set `JKResolver` needs: it is resolving names *seen in the Java
 * file being converted*.
 */
object JavaFullClassNameIndex {

    /**
     * Returns the index instance.
     *
     * @return this singleton.
     */
    @JvmStatic
    fun getInstance(): JavaFullClassNameIndex = this

    /**
     * Returns the classes with the fully qualified name [fqName].
     *
     * @param fqName the fully qualified class name.
     * @param project the project that owns the PSI.
     * @param scope the search scope.
     * @return the matching classes, possibly empty.
     */
    fun getClasses(fqName: String, project: Project, scope: GlobalSearchScope): List<PsiClass> =
        JavaPsiFacade.getInstance(project).findClasses(fqName, scope).toList()
}
