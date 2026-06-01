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

import com.intellij.lang.Language;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for IntelliJ's {@code CodeStyleAbstractPanel} that is sufficient
 * for the Kotlin formatter UI panels ({@code KotlinOtherSettingsPanel},
 * {@code KotlinSaveStylePanel}, {@code ImportSettingsPanel}).
 *
 * <p>The original class embeds an IntelliJ editor for live preview and uses
 * {@code EditorColorsScheme} / {@code PsiFileFactory} / {@code UndoManager}.
 * None of that infrastructure is available in a NetBeans runtime.  The Kotlin
 * subclasses already throw {@link UnsupportedOperationException} for
 * {@link #createHighlighter} and {@link #getFileType}, so the editor-preview
 * path is never invoked.  This stub omits it entirely.
 *
 * <p>NetBeans preview is provided separately via
 * {@code KotlinFormattingPreviewProvider} using the Kotlin editor's
 * {@code EditorKit}.
 */
public abstract class CodeStyleAbstractPanel {

    /** The settings model instance passed at construction time. */
    @NotNull private final CodeStyleSettings settings;

    /**
     * Constructs the panel with the given settings.
     *
     * @param language       the language this panel configures (unused in the stub)
     * @param currentSettings the "before" snapshot used by IDEA's diff; may be {@code null}
     * @param settings       the live settings model that {@link #apply} and
     *                       {@link #resetImpl} read from / write to
     */
    protected CodeStyleAbstractPanel(@Nullable Language language,
                                     @Nullable CodeStyleSettings currentSettings,
                                     @NotNull CodeStyleSettings settings) {
        this.settings = settings;
    }

    /**
     * Returns the settings model this panel operates on.
     *
     * @return the code-style settings instance
     */
    @NotNull
    protected CodeStyleSettings getSettings() {
        return settings;
    }

    /**
     * Called when the user changes any value inside the panel.  Subclasses may
     * override to trigger validation or cross-panel updates.
     */
    protected void onSomethingChanged() {}

    // -------------------------------------------------------------------------
    // Abstract API — implemented by Kotlin subclasses
    // -------------------------------------------------------------------------

    /**
     * Returns the display title for this panel's tab in the parent tabbed pane.
     *
     * @return non-null tab title
     */
    @NotNull
    public abstract String getTabTitle();

    /**
     * Applies the current UI state to {@code settings}.
     *
     * @param settings the settings model to update
     */
    public abstract void apply(@NotNull CodeStyleSettings settings);

    /**
     * Returns {@code true} if the UI state differs from {@code settings}.
     *
     * @param settings the settings model to compare against
     * @return {@code true} if there are unsaved changes
     */
    public abstract boolean isModified(@NotNull CodeStyleSettings settings);

    /**
     * Resets the UI to reflect the values in {@code settings}.
     * Delegates to {@link #resetImpl}.
     *
     * @param settings the settings model to read from
     */
    public void reset(@NotNull CodeStyleSettings settings) {
        resetImpl(settings);
    }

    /**
     * Template method called by {@link #reset}.  Subclasses implement this to
     * update their widgets from the supplied settings.
     *
     * @param settings the settings model to read from
     */
    protected abstract void resetImpl(@NotNull CodeStyleSettings settings);

    /**
     * Returns the root Swing component of this panel.
     *
     * @return the panel's root component
     */
    @NotNull
    public abstract JComponent getPanel();

    /**
     * Returns the Kotlin code snippet shown in the editor preview, or
     * {@code null} if no preview is desired for this category.
     *
     * @return preview source text, or {@code null}
     */
    @Nullable
    public abstract String getPreviewText();

    // -------------------------------------------------------------------------
    // Unsupported editor-preview hooks — always throw in the Kotlin subclasses
    // -------------------------------------------------------------------------

    /**
     * Not used in the NetBeans runtime.  The Kotlin subclasses throw
     * {@link UnsupportedOperationException} for this method.
     *
     * @param scheme editor color scheme (ignored)
     * @return never returns normally
     */
    @Nullable
    public EditorHighlighter createHighlighter(@Nullable EditorColorsScheme scheme) {
        throw new UnsupportedOperationException("No IntelliJ editor in NetBeans runtime");
    }

    /**
     * Not used in the NetBeans runtime.  The Kotlin subclasses throw
     * {@link UnsupportedOperationException} for this method.
     *
     * @return never returns normally
     */
    @Nullable
    public FileType getFileType() {
        throw new UnsupportedOperationException("No IntelliJ editor in NetBeans runtime");
    }

    /**
     * Not used in the NetBeans runtime.  Returns {@code -1} as a sentinel.
     *
     * @return {@code -1}
     */
    public int getRightMargin() {
        return -1;
    }
}
