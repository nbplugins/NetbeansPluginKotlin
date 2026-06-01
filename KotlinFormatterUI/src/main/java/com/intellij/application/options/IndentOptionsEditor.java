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
import javax.swing.*;

/**
 * Minimal stub for IndentOptionsEditor.
 */
public abstract class IndentOptionsEditor {

    /** @return the UI component for this editor */
    public abstract @NotNull JComponent createComponent();

    /** @return true if the settings differ from the UI */
    public abstract boolean isModified(@NotNull CodeStyleSettings settings,
                                       @NotNull CodeStyleSettings.IndentOptions options);

    /** Applies the UI state to the settings. */
    public abstract void apply(@NotNull CodeStyleSettings settings,
                               @NotNull CodeStyleSettings.IndentOptions options);

    /** Resets the UI state from the settings. */
    public abstract void reset(@NotNull CodeStyleSettings settings,
                               @NotNull CodeStyleSettings.IndentOptions options);
}
