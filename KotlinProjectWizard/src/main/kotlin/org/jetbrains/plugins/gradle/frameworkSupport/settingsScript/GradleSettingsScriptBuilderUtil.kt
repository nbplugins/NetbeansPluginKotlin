// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.plugins.gradle.frameworkSupport.settingsScript

import org.gradle.util.GradleVersion

/**
 * Returns the Foojay toolchain-resolver plugin version compatible with [gradleVersion].
 *
 * Copied from `plugins/gradle` in IntelliJ Community, with the Gradle tooling-extension
 * dependency replaced by the local [GradleVersion] stub.
 *
 * @param gradleVersion the Gradle version the generated build uses
 * @return the newest plugin version supported by that Gradle
 */
fun getFoojayPluginVersion(gradleVersion: GradleVersion): String {
    return if (gradleVersion < GradleVersion.version("9.0")) "0.8.0" else "1.0.0"
}

/**
 * Tells whether the Foojay toolchain-resolver plugin can be added to a build with
 * [gradleVersion]. Gradle supports it from 7.6 on.
 */
fun isFoojayPluginSupported(gradleVersion: GradleVersion): Boolean {
    return gradleVersion >= GradleVersion.version("7.6")
}
