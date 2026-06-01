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

import java.lang.reflect.Field;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal stub for
 * {@code com.intellij.application.options.codeStyle.properties.CodeStyleFieldAccessor}.
 *
 * <p>Returned by {@code KotlinLanguageCodeStyleSettingsProvider.getAccessor()} for
 * {@code KotlinPackageEntryTable} fields.
 *
 * @param <T> the object type that owns the field
 * @param <V> the value type of the field
 */
public abstract class CodeStyleFieldAccessor<T, V> extends CodeStylePropertyAccessor<V> {

    /** The object that owns the field. */
    @NotNull protected final T object;

    /** The reflected field. */
    @NotNull protected final Field field;

    /**
     * @param object the object that owns {@code field}
     * @param field  the reflected field to access
     */
    protected CodeStyleFieldAccessor(@NotNull T object, @NotNull Field field) {
        this.object = object;
        this.field = field;
    }
}
