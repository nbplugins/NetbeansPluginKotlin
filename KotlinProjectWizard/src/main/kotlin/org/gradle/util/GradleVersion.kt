// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.gradle.util

import com.intellij.util.text.VersionComparatorUtil

/**
 * Minimal standalone replacement for Gradle's `org.gradle.util.GradleVersion`.
 *
 * The Kotlin New Project Wizard only needs to parse a version string, compare two versions
 * and print one back into generated build files. The real class ships with Gradle's tooling
 * API, which is not packaged into the NetBeans plugin; this stub keeps the same comparison
 * semantics by delegating to IDEA's [VersionComparatorUtil].
 *
 * @property version normalized version string, as passed to [version]
 */
class GradleVersion private constructor(private val version: String) : Comparable<GradleVersion> {

    /**
     * Compares this version with [other] using IDEA's version comparator (handles missing
     * segments, numeric ordering and suffixes such as `-rc-1`).
     */
    override fun compareTo(other: GradleVersion): Int = VersionComparatorUtil.compare(version, other.version)

    override fun equals(other: Any?): Boolean = other is GradleVersion && compareTo(other) == 0

    override fun hashCode(): Int = version.hashCode()

    override fun toString(): String = version

    companion object {
        /**
         * Parses [version] into a comparable value.
         *
         * @param version a Gradle version string, e.g. `"8.14.3"`
         */
        @JvmStatic
        fun version(version: String): GradleVersion = GradleVersion(version)

        /**
         * Returns the Gradle version recommended by the NetBeans wizard when no wrapper is used.
         */
        @JvmStatic
        fun current(): GradleVersion = GradleVersion("8.14.3")
    }
}
