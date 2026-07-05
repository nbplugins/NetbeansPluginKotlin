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
package org.jetbrains.kotlin.idea.base.facet

import com.intellij.openapi.module.Module

/**
 * Stub of IDEA's `org.jetbrains.kotlin.idea.base.facet.implementedModules` (Kotlin Multiplatform
 * "expect" module's set of "actual" platform modules it's implemented by). This plugin does not
 * support Kotlin Multiplatform facets — every module implements nothing else, so an empty set is
 * the correct answer, not a simplification.
 */
val Module.implementedModules: List<Module> get() = emptyList()
