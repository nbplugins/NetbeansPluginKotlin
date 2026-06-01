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
package com.intellij.ui.components;

import java.awt.Component;
import javax.swing.JScrollPane;

/**
 * Minimal stub for {@code com.intellij.ui.components.JBScrollPane}.
 *
 * <p>The full IntelliJ version uses internal {@code ScrollSettings} and
 * {@code ScrollBarPainter} APIs not available outside the IntelliJ runtime.
 * This stub delegates to {@link JScrollPane}.
 */
public class JBScrollPane extends JScrollPane {

    /** Creates an empty scroll pane. */
    public JBScrollPane() {
        super();
    }

    /**
     * Creates a scroll pane wrapping the given view component.
     *
     * @param view the component to display in the scroll pane
     */
    public JBScrollPane(Component view) {
        super(view);
    }

    /**
     * Creates a scroll pane with the given scroll-bar policies.
     *
     * @param vsbPolicy  vertical scroll-bar policy constant
     * @param hsbPolicy  horizontal scroll-bar policy constant
     */
    public JBScrollPane(int vsbPolicy, int hsbPolicy) {
        super(vsbPolicy, hsbPolicy);
    }

    /**
     * Creates a scroll pane wrapping the given view with explicit scroll-bar policies.
     *
     * @param view       the component to display in the scroll pane
     * @param vsbPolicy  vertical scroll-bar policy constant
     * @param hsbPolicy  horizontal scroll-bar policy constant
     */
    public JBScrollPane(Component view, int vsbPolicy, int hsbPolicy) {
        super(view, vsbPolicy, hsbPolicy);
    }
}
