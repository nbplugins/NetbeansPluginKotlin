/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package com.intellij.java.analysis

/**
 * Stub of IntelliJ's `com.intellij.java.analysis.JavaAnalysisBundle` message bundle. Only the one
 * key used by [moveUtil.presentablePkgName][org.jetbrains.kotlin.idea.k2.refactoring.move.processor.presentablePkgName]
 * is provided.
 */
object JavaAnalysisBundle {
    fun message(key: String, vararg params: Any): String = when (key) {
        "inspection.reference.default.package" -> "default package"
        else -> key
    }
}
