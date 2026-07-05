/*******************************************************************************
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
 *
 *******************************************************************************/
package com.intellij.openapi.module;

/**
 * Runtime-precedence twin of {@code KotlinRefactoring}'s {@code
 * com.intellij.openapi.module.ModuleType} — see {@link SingletonModule}'s doc comment for why this
 * duplicate exists. Only {@link #isInternal(Module)} is referenced by the ported Move Declaration
 * conflict checks — always {@code false} since this plugin never creates IntelliJ's special
 * "internal plugin" module type.
 */
public final class ModuleType {
    private ModuleType() {}

    public static boolean isInternal(Module module) {
        return false;
    }
}
