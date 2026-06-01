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
package com.intellij.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for {@code com.intellij.ui.ToolbarDecorator}.
 *
 * <p>The full IntelliJ version provides a rich toolbar with icon buttons and
 * drag-and-drop reordering.  This stub renders plain text buttons (Add, Remove,
 * Up, Down) so that the formatter settings panels compile and function correctly
 * in the NetBeans runtime without the IntelliJ toolbar infrastructure.
 */
public final class ToolbarDecorator {

    private final JTable table;
    private Runnable addAction;
    private Runnable removeAction;
    private Runnable moveUpAction;
    private Runnable moveDownAction;
    private AnAction extraAction;
    private java.util.function.BooleanSupplier removeActionUpdater;
    private Dimension preferredSize;

    private ToolbarDecorator(@NotNull JTable table) {
        this.table = table;
    }

    /**
     * Creates a decorator for the given table.
     *
     * @param table the table to decorate
     * @return a new decorator builder
     */
    @NotNull
    public static ToolbarDecorator createDecorator(@NotNull JTable table) {
        return new ToolbarDecorator(table);
    }

    /**
     * Sets the action to invoke when the Add button is clicked.
     *
     * @param action the add callback
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setAddAction(@NotNull Consumer<? super AnActionButton> action) {
        this.addAction = () -> action.accept(null);
        return this;
    }

    /**
     * Sets the action to invoke when the Remove button is clicked.
     *
     * @param action the remove callback
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setRemoveAction(@NotNull Consumer<? super AnActionButton> action) {
        this.removeAction = () -> action.accept(null);
        return this;
    }

    /**
     * Sets the action to invoke when the Move Up button is clicked.
     *
     * @param action the move-up callback
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setMoveUpAction(@NotNull Consumer<? super AnActionButton> action) {
        this.moveUpAction = () -> action.accept(null);
        return this;
    }

    /**
     * Sets the action to invoke when the Move Down button is clicked.
     *
     * @param action the move-down callback
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setMoveDownAction(@NotNull Consumer<? super AnActionButton> action) {
        this.moveDownAction = () -> action.accept(null);
        return this;
    }

    /**
     * Adds an extra toolbar action.  In this stub the action is exposed as an
     * additional button using the action's text from its template presentation.
     *
     * @param action the extra action
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator addExtraAction(@NotNull AnAction action) {
        this.extraAction = action;
        return this;
    }

    /**
     * Sets a predicate that controls whether the Remove button is enabled.
     * The predicate is evaluated on every table-selection change.
     *
     * @param updater supplier returning {@code true} when remove is allowed
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setRemoveActionUpdater(
            @NotNull java.util.function.Supplier<Boolean> updater) {
        this.removeActionUpdater = updater::get;
        return this;
    }

    /**
     * Sets the preferred size hint for the decorated panel.
     *
     * @param size preferred size ({@code -1} for a dimension means "use default")
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setPreferredSize(@NotNull Dimension size) {
        this.preferredSize = size;
        return this;
    }

    /**
     * Sets the ordering of toolbar buttons.  Ignored in this stub (buttons are
     * always laid out Add → Remove → Up → Down).
     *
     * @param names button labels in desired order (ignored)
     * @return {@code this} for chaining
     */
    @NotNull
    public ToolbarDecorator setButtonComparator(String... names) {
        return this;
    }

    /**
     * Builds and returns the decorated panel.
     *
     * @return a {@link JPanel} containing the table and a side toolbar
     */
    @NotNull
    public JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(table, BorderLayout.CENTER);

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));

        if (addAction != null) {
            JButton btn = new JButton("Add");
            Runnable action = addAction;
            btn.addActionListener(e -> action.run());
            toolbar.add(btn);
        }
        if (extraAction != null) {
            String text = extraAction.getTemplatePresentation().getText();
            JButton btn = new JButton(text != null ? text : "…");
            AnAction a = extraAction;
            btn.addActionListener(e -> a.actionPerformed(new AnActionEvent(null, key -> null, "", a.getTemplatePresentation(), null, 0)));
            toolbar.add(btn);
        }
        if (removeAction != null) {
            JButton btn = new JButton("Remove");
            Runnable action = removeAction;
            btn.addActionListener(e -> action.run());
            if (removeActionUpdater != null) {
                table.getSelectionModel().addListSelectionListener(ev ->
                    btn.setEnabled(removeActionUpdater.getAsBoolean()));
                btn.setEnabled(removeActionUpdater.getAsBoolean());
            }
            toolbar.add(btn);
        }
        if (moveUpAction != null) {
            JButton btn = new JButton("Up");
            Runnable action = moveUpAction;
            btn.addActionListener(e -> action.run());
            toolbar.add(btn);
        }
        if (moveDownAction != null) {
            JButton btn = new JButton("Down");
            Runnable action = moveDownAction;
            btn.addActionListener(e -> action.run());
            toolbar.add(btn);
        }
        toolbar.add(Box.createVerticalGlue());

        panel.add(toolbar, BorderLayout.EAST);

        if (preferredSize != null) {
            int w = preferredSize.width < 0 ? panel.getPreferredSize().width : preferredSize.width;
            int h = preferredSize.height < 0 ? panel.getPreferredSize().height : preferredSize.height;
            panel.setPreferredSize(new Dimension(w, h));
        }
        return panel;
    }

    /** Placeholder for the {@code AnActionButton} type used in action callbacks. */
    public static final class AnActionButton {}
}
