package org.netbeans.modules.gradle

import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject
import org.openide.util.Lookup

/**
 * Test-only stand-in for the project class of Apache NetBeans's built-in Gradle module
 * (bundled since the Gradle Inc. donation, NB 12+). Only the fully-qualified class name
 * matters for the tests using it — production code detects Gradle projects by comparing
 * against this exact name.
 */
class NbGradleProjectImpl(
    private val projectDirectory: FileObject,
    private val projectLookup: Lookup = Lookup.EMPTY
) : Project {
    override fun getProjectDirectory(): FileObject = projectDirectory
    override fun getLookup(): Lookup = projectLookup
}
