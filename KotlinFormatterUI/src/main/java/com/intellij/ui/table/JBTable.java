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
package com.intellij.ui.table;

import javax.swing.JTable;
import javax.swing.table.TableModel;

/**
 * Minimal stub for {@code com.intellij.ui.table.JBTable}.
 *
 * <p>The full IntelliJ version uses {@code sun.swing.SwingUtilities2} which is an
 * internal JDK API inaccessible in Java 17+.  This stub delegates to {@link JTable}
 * which provides equivalent behaviour for the formatter settings panels.
 */
public class JBTable extends JTable {

    /** Creates an empty table. */
    public JBTable() {
        super();
    }

    /**
     * Creates a table backed by the given model.
     *
     * @param model the data model
     */
    public JBTable(TableModel model) {
        super(model);
    }

    /**
     * Returns whether the table uses striped row rendering.
     *
     * @return {@code false} — striping is not available in this stub
     */
    public boolean isStriped() {
        return false;
    }

    /**
     * Sets striped row rendering.  No-op in this stub.
     *
     * @param striped ignored
     */
    public void setStriped(boolean striped) {}
}
