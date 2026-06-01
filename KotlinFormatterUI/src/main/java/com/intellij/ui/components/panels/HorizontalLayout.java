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
package com.intellij.ui.components.panels;

import java.awt.FlowLayout;

/**
 * Minimal stub for {@code com.intellij.ui.components.panels.HorizontalLayout}.
 *
 * <p>In IntelliJ this is a custom layout that stacks components horizontally
 * with a configurable gap.  In the NetBeans runtime we delegate to
 * {@link FlowLayout#LEFT} which provides equivalent behaviour for the formatter
 * settings panels.
 *
 * @see com.intellij.ui.components.panels.VerticalLayout
 */
public class HorizontalLayout extends FlowLayout {

    /**
     * Creates a horizontal layout with the specified gap between components.
     *
     * @param gap horizontal gap in pixels between adjacent components
     */
    public HorizontalLayout(int gap) {
        super(FlowLayout.LEFT, gap, 0);
    }
}
