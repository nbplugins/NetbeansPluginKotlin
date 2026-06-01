/*
 * Copyright 2000-2025 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.intellij.openapi.ui;

import javax.swing.*;

/**
 * Minimal stub for ComboBox — delegates to JComboBox with no extra IntelliJ behaviour.
 *
 * @param <E> the element type
 */
public class ComboBox<E> extends JComboBox<E> {

    /** Creates an empty ComboBox. */
    public ComboBox() { super(); }

    /**
     * Creates a ComboBox with the given items.
     *
     * @param items initial items
     */
    public ComboBox(E[] items) { super(items); }

    /**
     * Creates a ComboBox with the given model.
     *
     * @param model the combo-box model
     */
    public ComboBox(ComboBoxModel<E> model) { super(model); }

    /**
     * Sets the minimum width of the combo box (no-op in this stub).
     *
     * @param minimumWidth minimum width in pixels
     */
    public void setMinimumAndPreferredWidth(int minimumWidth) {}
}
