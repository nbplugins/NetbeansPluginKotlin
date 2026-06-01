/*
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
 */
package com.intellij.openapi.actionSystem;

/**
 * Minimal stub for {@code com.intellij.openapi.actionSystem.CommonShortcuts}.
 *
 * <p>Only the {@link #getNewForDialogs()} factory method used by
 * {@code BaseKotlinImportLayoutPanel} is implemented.
 */
public final class CommonShortcuts {

    private CommonShortcuts() {}

    /**
     * Returns the keyboard shortcut set used for "New" actions inside dialogs.
     *
     * @return an empty shortcut set (keyboard shortcuts are not active in the
     *         NetBeans runtime)
     */
    public static ShortcutSet getNewForDialogs() {
        return new CustomShortcutSet(new Shortcut[0]);
    }
}
