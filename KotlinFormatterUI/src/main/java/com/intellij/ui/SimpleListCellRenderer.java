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
import java.util.function.BiConsumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal stub for {@code com.intellij.ui.SimpleListCellRenderer}.
 *
 * <p>The full IntelliJ version uses {@code sun.swing.DefaultLookup} which is an
 * internal JDK API inaccessible in Java 17+.  This stub delegates to
 * {@link DefaultListCellRenderer} and implements the {@link #customize} pattern
 * and the {@link #create} factory used by the formatter settings panels.
 *
 * @param <T> the type of elements in the list
 */
public abstract class SimpleListCellRenderer<T> extends DefaultListCellRenderer {

    /**
     * Called to set the label text and icon for the given list element.
     *
     * @param label the label component to configure
     * @param value the list element to render (may be {@code null} when the combo
     *              box measures the baseline before any item is set)
     * @param index the element index; {@code -1} when rendering the selected item
     *              outside the drop-down
     */
    public abstract void customize(@NotNull JLabel label, T value, int index);

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (index != -1 || value != null) {
            customize(this, (T) value, index);
        }
        return this;
    }

    /**
     * Creates a renderer from a lambda that configures the label.
     *
     * @param <T>      the element type
     * @param consumer a function {@code (label, value) -> label.setText(...)}
     * @return a new renderer backed by the given consumer
     */
    @NotNull
    public static <T> SimpleListCellRenderer<T> create(
            @NotNull BiConsumer<? super JLabel, ? super T> consumer) {
        return new SimpleListCellRenderer<T>() {
            @Override
            public void customize(@NotNull JLabel label, T value, int index) {
                consumer.accept(label, value);
            }
        };
    }
}
