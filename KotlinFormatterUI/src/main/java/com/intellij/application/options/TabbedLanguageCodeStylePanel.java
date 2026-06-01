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
import com.intellij.psi.codeStyle.CodeStyleSettings;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for IntelliJ's {@code TabbedLanguageCodeStylePanel} that hosts
 * multiple {@link CodeStyleAbstractPanel} instances as tabs.
 *
 * <p>The only Kotlin subclass in the formatter UI is {@code KotlinCodeStylePanel},
 * which overrides {@link #initTabs} to add category-specific sub-panels.  This
 * stub provides the tabbed-pane container and delegates {@code apply},
 * {@code isModified}, {@code resetImpl} to all registered tabs.
 */
public abstract class TabbedLanguageCodeStylePanel extends CodeStyleAbstractPanel {

    /** All sub-panels registered via {@link #addTab}. */
    private final List<CodeStyleAbstractPanel> tabs = new ArrayList<>();

    /** The Swing tabbed pane that displays sub-panels. */
    private final JTabbedPane tabbedPane = new JTabbedPane();

    /**
     * Constructs the tabbed panel.  Immediately calls {@link #initTabs} so that
     * sub-panels are added before the component is shown.
     *
     * @param language       the language being configured
     * @param currentSettings before-snapshot (may be {@code null})
     * @param settings        the live settings model
     */
    protected TabbedLanguageCodeStylePanel(@Nullable Language language,
                                           @Nullable CodeStyleSettings currentSettings,
                                           @NotNull CodeStyleSettings settings) {
        super(language, currentSettings, settings);
        initTabs(settings);
    }

    /**
     * Called from the constructor to populate the tabbed pane.  Subclasses
     * override this to call {@link #addTab} for each category panel.
     *
     * @param settings the settings model passed through to each sub-panel
     */
    protected abstract void initTabs(@NotNull CodeStyleSettings settings);

    /**
     * Registers a sub-panel as a new tab.
     *
     * @param panel the sub-panel to add; its {@link CodeStyleAbstractPanel#getTabTitle()}
     *              becomes the tab label
     */
    protected void addTab(@NotNull CodeStyleAbstractPanel panel) {
        tabs.add(panel);
        JComponent component = panel.getPanel();
        if (component != null) {
            tabbedPane.addTab(panel.getTabTitle(), component);
        }
    }

    // -------------------------------------------------------------------------
    // CodeStyleAbstractPanel implementation — delegates to all tabs
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public String getTabTitle() {
        return "Kotlin";
    }

    /** {@inheritDoc} Delegates to all registered sub-panels. */
    @Override
    public void apply(@NotNull CodeStyleSettings settings) {
        for (CodeStyleAbstractPanel tab : tabs) {
            tab.apply(settings);
        }
    }

    /** {@inheritDoc} Returns {@code true} if any registered sub-panel is modified. */
    @Override
    public boolean isModified(@NotNull CodeStyleSettings settings) {
        for (CodeStyleAbstractPanel tab : tabs) {
            if (tab.isModified(settings)) return true;
        }
        return false;
    }

    /** {@inheritDoc} Delegates to all registered sub-panels. */
    @Override
    protected void resetImpl(@NotNull CodeStyleSettings settings) {
        for (CodeStyleAbstractPanel tab : tabs) {
            tab.resetImpl(settings);
        }
    }

    /** Returns the root tabbed-pane component. */
    @Override
    @NotNull
    public JComponent getPanel() {
        return tabbedPane;
    }

    /** Not applicable for the tabbed container; returns {@code null}. */
    @Override
    @Nullable
    public String getPreviewText() {
        return null;
    }
}
