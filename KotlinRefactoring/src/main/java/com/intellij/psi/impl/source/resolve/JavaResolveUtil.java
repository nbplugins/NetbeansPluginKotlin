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
package com.intellij.psi.impl.source.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierList;

/**
 * Stub of IntelliJ's {@code com.intellij.psi.impl.source.resolve.JavaResolveUtil} (not present in
 * our checked-out Community sources). Only used by Move Declaration's visibility conflict check
 * for its Java-light-class fallback path ({@code lightIsVisibleTo}); this plugin's Kotlin light
 * classes are not fully functional standalone (no full Java PSI compiler backing them), so
 * conservatively reporting "accessible" avoids false-positive conflicts for a code path that is
 * secondary to Move's main Kotlin-to-Kotlin visibility checks (handled separately via the K2
 * Analysis API's {@code createUseSiteVisibilityChecker}, which is real and precise).
 */
public final class JavaResolveUtil {
    private JavaResolveUtil() {}

    public static boolean isAccessible(
        PsiElement place,
        PsiClass accessObjectClass,
        PsiModifierList modifierList,
        PsiElement placeToCheck,
        PsiClass accessClass,
        Object unused
    ) {
        return true;
    }
}
