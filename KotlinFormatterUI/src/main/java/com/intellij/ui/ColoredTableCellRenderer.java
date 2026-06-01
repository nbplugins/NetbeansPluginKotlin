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

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for {@code com.intellij.ui.ColoredTableCellRenderer}.
 *
 * <p>The full IntelliJ version extends {@code SimpleColoredRenderer} (from
 * {@code ide:253}) which is not available in this build context.  This stub
 * delegates to {@link DefaultTableCellRenderer} and exposes the same
 * {@link #customizeCellRenderer} hook used by
 * {@code BaseKotlinImportLayoutPanel}'s anonymous renderer.
 */
public abstract class ColoredTableCellRenderer extends DefaultTableCellRenderer {

    /**
     * Called to configure the renderer label for a given cell.
     *
     * @param table     the table being rendered
     * @param value     the cell value
     * @param selected  whether the cell is selected
     * @param hasFocus  whether the cell has keyboard focus
     * @param row       the row index
     * @param column    the column index
     */
    public abstract void customizeCellRenderer(
        @NotNull JTable table,
        @Nullable Object value,
        boolean selected,
        boolean hasFocus,
        int row,
        int column
    );

    /** {@inheritDoc} Delegates to {@link #customizeCellRenderer}. */
    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus,
            int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        customizeCellRenderer(table, value, isSelected, hasFocus, row, column);
        return this;
    }

    /**
     * Appends styled text to the renderer label.
     *
     * <p>In this stub the {@code attributes} are ignored and the fragment is
     * appended to the label text.
     *
     * @param fragment   the text fragment to append
     * @param attributes text attributes (ignored in this stub)
     */
    public void append(@NotNull String fragment, @NotNull Object attributes) {
        String current = getText();
        setText(current == null ? fragment : current + fragment);
    }

    /**
     * Appends plain text to the renderer label.
     *
     * @param fragment the text fragment to append
     */
    public void append(@NotNull String fragment) {
        String current = getText();
        setText(current == null ? fragment : current + fragment);
    }
}
