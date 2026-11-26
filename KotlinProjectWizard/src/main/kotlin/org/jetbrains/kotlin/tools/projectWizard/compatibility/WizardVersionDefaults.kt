// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.tools.projectWizard.compatibility

/**
 * Builds the wizard version state that the NetBeans plugin injects into
 * [KotlinWizardVersionStore].
 *
 * [DEFAULT_KOTLIN_WIZARD_VERSIONS] is `internal` to this module and pins the Kotlin version
 * shipped by the current IntelliJ platform (2.3.10). The NetBeans plugin must generate
 * projects for its own Analysis API version, so [kotlinPluginVersion] overrides only that one
 * field and keeps every other default.
 *
 * @param kotlinPluginVersion Kotlin version written into generated build files
 * @return a copy of the defaults with the Kotlin plugin version replaced
 */
fun kotlinWizardVersionState(kotlinPluginVersion: String): KotlinWizardVersionState =
    KotlinWizardVersionState().also { state ->
        state.copyFrom(DEFAULT_KOTLIN_WIZARD_VERSIONS)
        state.kotlinPluginVersion = kotlinPluginVersion
    }
