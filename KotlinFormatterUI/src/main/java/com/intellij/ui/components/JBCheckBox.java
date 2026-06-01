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
package com.intellij.ui.components;

import org.jetbrains.annotations.Nullable;
import javax.swing.*;

/**
 * Minimal stub for JBCheckBox — delegates to JCheckBox with no extra IntelliJ behaviour.
 */
public class JBCheckBox extends JCheckBox {

    /** Creates an unselected check box with no text. */
    public JBCheckBox() { super(); }

    /**
     * Creates an unselected check box with the given label.
     *
     * @param text the check-box label
     */
    public JBCheckBox(@Nullable String text) { super(text != null ? text : ""); }

    /**
     * Creates a check box with the given label and initial state.
     *
     * @param text     the check-box label
     * @param selected initial selected state
     */
    public JBCheckBox(@Nullable String text, boolean selected) {
        super(text != null ? text : "", selected);
    }
}
