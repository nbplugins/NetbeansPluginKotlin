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
package com.intellij.application.options;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for {@code com.intellij.application.options.CodeStyleAbstractConfigurable}.
 *
 * <p>Only the constructor signature and abstract method {@link #createPanel} used
 * by {@code KotlinLanguageCodeStyleSettingsProvider} are provided.  The IntelliJ
 * version extends {@code NamedConfigurable} and manages an IDE settings panel;
 * that infrastructure is absent in the NetBeans runtime.
 */
public abstract class CodeStyleAbstractConfigurable implements CodeStyleConfigurable {

    /** The "before" settings snapshot. */
    @NotNull protected final CodeStyleSettings currentSettings;

    /** The live settings model. */
    @NotNull protected final CodeStyleSettings settings;

    /** The display name shown in the settings tree. */
    @NotNull private final String displayName;

    /**
     * @param currentSettings "before" snapshot (used by IDEA diff; may equal {@code settings})
     * @param settings        the live settings model
     * @param displayName     the node label in the settings tree
     */
    protected CodeStyleAbstractConfigurable(@NotNull CodeStyleSettings currentSettings,
                                            @NotNull CodeStyleSettings settings,
                                            @NotNull String displayName) {
        this.currentSettings = currentSettings;
        this.settings = settings;
        this.displayName = displayName;
    }

    /**
     * Creates the main settings panel for this configurable.
     *
     * @param settings the live settings model
     * @return the settings panel
     */
    @NotNull
    protected abstract CodeStyleAbstractPanel createPanel(@NotNull CodeStyleSettings settings);

    /**
     * Returns the IntelliJ help topic ID for this settings page.
     *
     * @return help topic string, or {@code null} if none
     */
    @Nullable
    public String getHelpTopic() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void apply() {}

    /** {@inheritDoc} */
    @Override
    public boolean isModified() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void reset() {}

    /** {@inheritDoc} Returns the configured display name. */
    @Override
    @NotNull
    public String getDisplayName() {
        return displayName;
    }
}
