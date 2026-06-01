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
package com.intellij.ui;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for {@code com.intellij.ui.IdeBorderFactory}.
 *
 * <p>The IntelliJ version creates {@code IdeaTitledBorder} instances (from
 * {@code ide:253}) which are not available in this build context.  This stub
 * falls back to standard Swing {@link TitledBorder}.
 */
public final class IdeBorderFactory {

    private IdeBorderFactory() {}

    /**
     * Creates a titled border with the given title.
     *
     * @param title the border title (may be {@code null})
     * @return a {@link TitledBorder}
     */
    public static Border createTitledBorder(@Nullable String title) {
        return BorderFactory.createTitledBorder(title);
    }

    /**
     * Creates a titled border.
     *
     * @param title      the border title (may be {@code null})
     * @param hasIndent  ignored in this stub
     * @return a {@link TitledBorder}
     */
    public static Border createTitledBorder(@Nullable String title, boolean hasIndent) {
        return BorderFactory.createTitledBorder(title);
    }

    /**
     * Creates a titled border.
     *
     * @param title      the border title (may be {@code null})
     * @param hasIndent  ignored in this stub
     * @param insets     ignored in this stub
     * @return a {@link TitledBorder}
     */
    public static Border createTitledBorder(@Nullable String title, boolean hasIndent,
                                            Object insets) {
        return BorderFactory.createTitledBorder(title);
    }
}
