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
package com.intellij.openapi.wm;

import java.awt.Component;

/**
 * Minimal stub for {@code com.intellij.openapi.wm.IdeFocusManager}.
 *
 * <p>Used by {@code BaseKotlinImportLayoutPanel} to request focus after a table
 * edit is committed.  In the NetBeans runtime the calls are no-ops.
 */
public abstract class IdeFocusManager {

    private static final IdeFocusManager INSTANCE = new IdeFocusManager() {};

    /**
     * Returns the global {@code IdeFocusManager} instance.
     *
     * @return the singleton stub instance
     */
    public static IdeFocusManager getGlobalInstance() {
        return INSTANCE;
    }

    /**
     * Schedules {@code runnable} to run when the focus has settled.
     * In this stub the runnable is invoked immediately.
     *
     * @param runnable the callback to invoke
     */
    public void doWhenFocusSettlesDown(Runnable runnable) {
        runnable.run();
    }

    /**
     * Requests focus on {@code component}.  No-op in this stub.
     *
     * @param component the component to focus (ignored)
     * @param forced    whether to force the focus transfer (ignored)
     */
    public void requestFocus(Component component, boolean forced) {}
}
