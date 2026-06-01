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
package com.intellij.application.options.codeStyle.properties;

import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for
 * {@code com.intellij.application.options.codeStyle.properties.CodeStylePropertyAccessor}.
 *
 * <p>Base class for code style property accessor returned by
 * {@code KotlinLanguageCodeStyleSettingsProvider.getAdditionalAccessors()}.
 *
 * @param <V> the value type
 */
public abstract class CodeStylePropertyAccessor<V> {

    /**
     * Returns the current property value, or {@code null} if it cannot be read.
     *
     * @return the value, or {@code null}
     */
    @Nullable
    public abstract V getValue();

    /**
     * Sets the property value.
     *
     * @param value the new value (may be {@code null} to reset to default)
     * @return {@code true} if the value was accepted
     */
    public abstract boolean setValue(@Nullable V value);
}
