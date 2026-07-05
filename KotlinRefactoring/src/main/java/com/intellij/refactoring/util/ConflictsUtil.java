/*******************************************************************************
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
package com.intellij.refactoring.util;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/**
 * Stub of IntelliJ's {@code com.intellij.refactoring.util.ConflictsUtil} (not present in our
 * checked-out Community sources). Only {@link #getContainer(PsiElement)} is needed by ported Move
 * Declaration conflict messages, as the fallback for non-Kotlin elements — the real class walks up
 * to the nearest method/field/class/file; for the non-Kotlin case (mostly Java light elements) the
 * containing file is an adequate, correctly-behaving fallback.
 */
public final class ConflictsUtil {
    private ConflictsUtil() {}

    public static PsiElement getContainer(PsiElement element) {
        PsiFile file = element.getContainingFile();
        return file != null ? file : element;
    }
}
