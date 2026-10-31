/*
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
 */
package io.github.nbplugins.kotlin.nbm.j2k

import org.netbeans.api.project.Project
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.netbeans.modules.refactoring.api.Problem
import org.openide.filesystems.FileObject
import org.openide.util.lookup.Lookups

/**
 * Carries the Java source file selected for conversion to Kotlin.
 *
 * The refactoring source is the Java [FileObject] rather than an editor document: the conversion
 * starts from a file node or from the Java editor, and its applicability must not depend on which
 * of the two places invoked it. [KotlinRefactoringsFactory][org.jetbrains.kotlin.refactorings
 * .rename.KotlinRefactoringsFactory] therefore recognizes this carrier before its
 * "editor document with a `.kt` file" guard.
 *
 * The conversion itself is performed by [JavaToKotlinPlugin]; this class only transports the
 * parameters and the failure message that the plugin has to report back to the caller.
 *
 * @param project the NetBeans project that owns [javaFile]
 * @param javaFile the Java source file to convert
 */
class JavaToKotlinRefactoring(
    val project: Project,
    javaFile: FileObject,
) : AbstractRefactoring(Lookups.fixed(javaFile)) {

    /**
     * The Java source file to convert.
     *
     * Replaced through [currentJavaFile] when undo recreates the file as a new `FileObject`, so
     * the element keeps a valid handle for a redo.
     */
    var javaFile: FileObject = javaFile
        private set

    /** Folder that contained the target when the refactoring was created, used to find it again. */
    private val javaParent: FileObject? = javaFile.parent

    /** Name and extension captured while the target still existed. */
    private val javaNameExt: String = javaFile.nameExt

    /**
     * Returns the Java file that currently represents the conversion target.
     *
     * `Undo Last Refactoring` recreates a deleted Java file with `createData`, which produces a new
     * `FileObject`; the handle captured when the action ran then points at a deleted file and a
     * redo would fail its `isValid` check. The current handle is looked up by the original parent
     * and name and cached, so the element's lookup and parent file are valid again.
     *
     * @return the current handle, or `null` when the conversion target does not exist (any more)
     */
    internal fun currentJavaFile(): FileObject? {
        if (javaFile.isValid) return javaFile
        javaParent?.getFileObject(javaNameExt)?.let { javaFile = it }
        return javaFile.takeIf { it.isValid }
    }

    /**
     * User-facing message of the last failed [JavaToKotlinElement.performChange] call.
     *
     * `performChange` cannot report failure through the refactoring API: the session registers its
     * undo entry even when an element throws. The element therefore records the reason here; it also
     * shows the message itself, because the Refactoring window closes without giving the caller a
     * chance to report it. [Java2KotlinConverter.convertNow] keeps the field for headless callers
     * and tests.
     */
    var failureMessage: String? = null

    /**
     * Checks that the Java file still exists and has no Kotlin sibling.
     *
     * Validation lives on the carrier rather than in [JavaToKotlinPlugin] because there are two
     * callers that must agree on it: the plugin (in `preCheck`/`prepare`) and
     * [ConvertToKotlinAction], which has to reject an unconvertible target before it opens the
     * Refactoring window.
     *
     * @return a fatal problem describing why the conversion cannot run, or `null` when it can
     */
    internal fun validate(): Problem? {
        val javaFile = currentJavaFile()
            ?: return Problem(true, "Convert to Kotlin requires an existing Java source file.")
        if (javaFile.isFolder) {
            return Problem(true, "Convert to Kotlin requires an existing Java source file.")
        }
        val parent = javaFile.parent
            ?: return Problem(true, "Convert to Kotlin requires a file inside a folder.")
        if (parent.getFileObject(javaFile.name, "kt") != null) {
            return Problem(true, "${parent.path}/${javaFile.name}.kt already exists.")
        }
        return null
    }
}
