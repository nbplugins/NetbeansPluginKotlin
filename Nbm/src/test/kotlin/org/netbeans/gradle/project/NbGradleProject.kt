package org.netbeans.gradle.project

import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject
import org.openide.util.Lookup

/**
 * Test-only stand-in for the project class of the old third-party "NetBeans Gradle Support"
 * plugin. Only the fully-qualified class name matters for the tests using it — production
 * code detects Gradle projects by comparing against this exact name.
 */
class NbGradleProject(
    private val projectDirectory: FileObject,
    private val projectLookup: Lookup = Lookup.EMPTY
) : Project {
    override fun getProjectDirectory(): FileObject = projectDirectory
    override fun getLookup(): Lookup = projectLookup
}
