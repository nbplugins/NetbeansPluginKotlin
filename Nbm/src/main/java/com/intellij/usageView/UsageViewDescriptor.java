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
package com.intellij.usageView;

import com.intellij.psi.PsiElement;

/**
 * Stub of IntelliJ's {@code com.intellij.usageView.UsageViewDescriptor}.
 * Needed at runtime so that subclasses of {@link com.intellij.refactoring.BaseRefactoringProcessor}
 * — e.g. {@code AbstractKotlinDeclarationInlineProcessor} — link successfully when the JVM resolves
 * their override of {@code createUsageViewDescriptor(UsageInfo[])}.
 *
 * <p>The plugin never invokes any method on instances of this interface; we only need the type
 * to exist at link time. The signatures mirror the upstream interface so Kotlin overrides
 * compiled against IDEA sources match by erasure.
 */
public interface UsageViewDescriptor {
    /** Returns the elements whose usages were searched. */
    PsiElement[] getElements();

    /** Header text for the "processed elements" section in the Usage View. */
    String getProcessedElementsHeader();

    /** Caption for the "code references" group in the Usage View. */
    String getCodeReferencesText(int usagesCount, int filesCount);

    /** Caption for the "comment references" group in the Usage View. */
    default String getCommentReferencesText(int usagesCount, int filesCount) {
        return null;
    }
}
