// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package com.intellij.ui.components.panels;

import org.jetbrains.annotations.NotNull;
import java.awt.*;

/** Package-private layout utility used by VerticalLayout. */
final class LayoutUtil {

    private LayoutUtil() {}

    /**
     * Returns the preferred size of the component, capped at its maximum size.
     *
     * @param component the component
     * @return the (possibly capped) preferred size
     */
    static @NotNull Dimension getPreferredSize(@NotNull Component component) {
        Dimension size = component.getPreferredSize();
        if (size == null) return new Dimension();
        if (component.isMaximumSizeSet()) {
            Dimension max = component.getMaximumSize();
            if (max != null) {
                if (size.width > max.width) size.width = max.width;
                if (size.height > max.height) size.height = max.height;
            }
        }
        return size;
    }
}
