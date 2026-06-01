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
package com.intellij.application.options;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;

/**
 * Minimal stub for SmartIndentOptionsEditor.
 * Returns a no-op JPanel; no IntelliJ editor infrastructure required.
 */
public class SmartIndentOptionsEditor extends IndentOptionsEditor {

    /** Creates a SmartIndentOptionsEditor with no UI customisations. */
    public SmartIndentOptionsEditor() {}

    @Override
    public @NotNull JComponent createComponent() {
        return new JPanel();
    }

    @Override
    public boolean isModified(@NotNull CodeStyleSettings settings,
                              @NotNull CodeStyleSettings.IndentOptions options) {
        return false;
    }

    @Override
    public void apply(@NotNull CodeStyleSettings settings,
                      @NotNull CodeStyleSettings.IndentOptions options) {}

    @Override
    public void reset(@NotNull CodeStyleSettings settings,
                      @NotNull CodeStyleSettings.IndentOptions options) {}
}
