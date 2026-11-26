// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.compiler.configuration

import com.intellij.util.text.VersionComparatorUtil

/**
 * Minimal standalone replacement for IDEA's `IdeKotlinVersion`.
 *
 * The Kotlin New Project Wizard core only asks [opt] for a parsed `major.minor.patch` triple
 * and then compares [kotlinVersion] with a threshold (the HMPP defaults check in
 * `MppModuleConfigurator`). Everything the real class does with JAR manifests, language
 * versions and build numbers is IDE-only and is not needed headlessly.
 *
 * @property rawVersion the version string exactly as supplied
 * @property kotlinVersion parsed numeric version (`major.minor.patch`)
 */
class IdeKotlinVersion private constructor(
    val rawVersion: String,
    val kotlinVersion: kotlin.KotlinVersion,
) : Comparable<IdeKotlinVersion> {

    override fun compareTo(other: IdeKotlinVersion): Int = VersionComparatorUtil.compare(rawVersion, other.rawVersion)

    override fun toString(): String = rawVersion

    companion object {
        /**
         * Parses [rawVersion] in the `major.minor.patch[-suffix]` form.
         *
         * @return the parsed version, or `null` if any of the first three components is not a number
         */
        @JvmStatic
        fun opt(rawVersion: String): IdeKotlinVersion? {
            val numeric = rawVersion.substringBefore('-').trim()
            val parts = numeric.split('.')
            if (parts.size < 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null
            return IdeKotlinVersion(rawVersion, kotlin.KotlinVersion(major, minor, patch))
        }

        /**
         * Like [opt], but throws [IllegalArgumentException] for an unparsable version.
         */
        @JvmStatic
        fun get(rawVersion: String): IdeKotlinVersion =
            opt(rawVersion) ?: throw IllegalArgumentException("Cannot parse Kotlin version: $rawVersion")

        /**
         * Parses [rawVersion] and wraps the outcome into a [Result].
         */
        @JvmStatic
        fun parse(rawVersion: String): Result<IdeKotlinVersion> = runCatching { get(rawVersion) }
    }
}
