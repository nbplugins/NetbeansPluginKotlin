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
package com.intellij.openapi.project;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.function.Supplier;

/**
 * Minimal stub for {@code com.intellij.openapi.project.DumbAwareAction}.
 *
 * <p>Extends {@link AnAction} and implements {@link DumbAware}.  The full
 * IntelliJ version also implements {@code ActionWithDelegate} and
 * {@code LightEditCompatible} — those interfaces are absent in this context
 * and are omitted.
 */
public abstract class DumbAwareAction extends AnAction implements DumbAware {

    /** Creates an action with no text or icon. */
    protected DumbAwareAction() {
        super();
    }

    /**
     * Creates an action with the given icon.
     *
     * @param icon the action icon (may be {@code null})
     */
    protected DumbAwareAction(@Nullable Icon icon) {
        super(icon);
    }

    /**
     * Creates an action with the given display text.
     *
     * @param text the action text (may be {@code null})
     */
    protected DumbAwareAction(@Nullable String text) {
        super(text);
    }

    /**
     * Creates an action with text, description, and icon.
     *
     * @param text        the action text (may be {@code null})
     * @param description the action description (may be {@code null})
     * @param icon        the action icon (may be {@code null})
     */
    protected DumbAwareAction(@Nullable String text,
                              @Nullable String description,
                              @Nullable Icon icon) {
        super(text, description, icon);
    }

    /** {@inheritDoc} Subclasses implement the action logic here. */
    @Override
    public abstract void actionPerformed(AnActionEvent e);
}
