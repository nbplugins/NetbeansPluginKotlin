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
package com.intellij.openapi.application;

import org.jetbrains.annotations.NotNull;

/**
 * Minimal stub for {@code com.intellij.openapi.application.ApplicationBundle}.
 *
 * <p>The IntelliJ version loads strings from {@code ApplicationBundle.properties}.
 * In the NetBeans runtime no such bundle exists, so {@link #message} returns the
 * key itself as a fallback.
 */
public final class ApplicationBundle {

    private ApplicationBundle() {}

    /**
     * Returns the localised string for the given key.  Falls back to the key
     * when the bundle is not available.
     *
     * @param key    the message key
     * @param params optional format parameters
     * @return the formatted message or the key if the bundle is unavailable
     */
    @NotNull
    public static String message(@NotNull String key, Object... params) {
        return key;
    }
}
