/*******************************************************************************
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
 *
 *******************************************************************************/
package org.jetbrains.kotlin.idea.base.plugin

/**
 * Kotlin plugin frontend mode.
 *
 * IDEA distinguishes K1 and K2 because both frontends can be installed at the same time. This
 * plugin is K2-only (stage C10 removed every K1 code path), so the mode is fixed.
 */
enum class KotlinPluginMode {
    /** The legacy K1 frontend. Never active in this plugin. */
    K1,

    /** The K2 (FIR) frontend. Always active in this plugin. */
    K2
}

/**
 * Standalone replacement for IDEA's `KotlinPluginModeProvider`
 * (`plugins/kotlin/base/plugin/src/org/jetbrains/kotlin/idea/base/plugin/KotlinPluginModeProvider.kt`).
 *
 * IDEA's version reads the mode from the registered `KotlinPluginModeProvider` service. This plugin
 * has no such service: the whole code base is K2-only after stage C10, so the provider simply
 * reports [KotlinPluginMode.K2].
 *
 * The J2K engine branches on the mode (`isK1Mode()` / `isK2Mode()`) to choose between the K1 and K2
 * conversion pipelines; hard-coding K2 selects the same pipeline the IDE uses.
 */
interface KotlinPluginModeProvider {

    /** The current plugin mode. */
    val pluginMode: KotlinPluginMode

    companion object {

        /** The current plugin mode — always [KotlinPluginMode.K2] in this plugin. */
        val currentPluginMode: KotlinPluginMode
            get() = KotlinPluginMode.K2

        /**
         * Returns whether the K2 frontend is active.
         *
         * @return always `true`.
         */
        @JvmStatic
        fun isK2Mode(): Boolean = currentPluginMode == KotlinPluginMode.K2

        /**
         * Returns whether the K1 frontend is active.
         *
         * @return always `false`.
         */
        @JvmStatic
        fun isK1Mode(): Boolean = currentPluginMode == KotlinPluginMode.K1
    }
}
