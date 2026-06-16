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
package io.github.nbplugins.kotlin.nbm.navigation

import org.jetbrains.kotlin.builder.isKotlinFile
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.java.project.JavaProjectConstants
import org.netbeans.api.project.FileOwnerQuery
import org.netbeans.api.project.Project
import org.netbeans.editor.BaseAction
import org.openide.awt.StatusDisplayer
import org.openide.cookies.OpenCookie
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent

/**
 * Editor action for **Ctrl+Alt+T** — "Go to Test / Tested class" for Kotlin files.
 *
 * Implemented as a self-contained Kotlin action rather than via the NetBeans
 * `org.netbeans.spi.gototest.TestLocator` SPI: the `gototest` module declares
 * friend-packages on `org.netbeans.spi.gototest`, so registering a
 * `TestLocator` from a non-friend module fails at runtime with
 * `ClassNotFoundException`. An implementation-version dependency would
 * bypass the friend restriction but binds to a specific gototest build hash
 * and asks the user to install a matching "Navigate To Test" build at
 * install time.
 *
 * Naming convention: a file `Foo.kt` is paired with `FooTest.kt`
 * (or `FooTests.kt`), and vice versa. The lookup scans all `SOURCES_TYPE_JAVA`
 * source roots of the owning project plus the well-known
 * `src/main/kotlin` / `src/test/kotlin` Maven/Gradle roots (which are not
 * exposed as `SOURCES_TYPE_JAVA` groups) recursively for a `.kt` file with
 * the matching base name. Candidates whose package directory matches the
 * source file's package are preferred.
 *
 * Registered under action name `"kotlin-goto-test"` in `layer.xml` for
 * `text/x-kotlin`.
 *
 * This class belongs to the **controller** layer.
 */
class KotlinGoToTestAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(SHORT_DESCRIPTION, "Go to Test/Tested class")
        putValue(POPUP_MENU_TEXT, "Go to Test/Tested class")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val log = KotlinLogger.INSTANCE
        val doc = target.document ?: return
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return
        log.logInfo("KotlinGoToTestAction.actionPerformed: input=${fo.path}")

        if (!fo.isKotlinFile()) {
            StatusDisplayer.getDefault().setStatusText("Not a Kotlin source file")
            return
        }

        val project = FileOwnerQuery.getOwner(fo)
        if (project == null) {
            log.logInfo("KotlinGoToTestAction: FileOwnerQuery.getOwner returned null")
            StatusDisplayer.getDefault().setStatusText("Cannot determine project for ${fo.nameExt}")
            return
        }

        val baseName = fo.name
        val isTest = baseName.endsWith("Test") || baseName.endsWith("Tests")
        val targetNames = if (isTest) {
            when {
                baseName.endsWith("Tests") -> listOf(baseName.removeSuffix("Tests"))
                else -> listOf(baseName.removeSuffix("Test"))
            }
        } else {
            listOf("${baseName}Test", "${baseName}Tests")
        }
        log.logInfo("KotlinGoToTestAction: isTest=$isTest baseName=$baseName targetNames=$targetNames")

        val roots = collectSourceRoots(project)
        log.logInfo("KotlinGoToTestAction: source roots (${roots.size}): ${roots.map { it.path }}")

        val sourceRelPath = sourceRootRelativePath(fo, roots)
        val candidates = roots.asSequence()
            .flatMap { root -> findKtFiles(root, targetNames).asSequence() }
            .toList()
        log.logInfo("KotlinGoToTestAction: candidates (${candidates.size}): ${candidates.map { it.path }}")

        if (candidates.isEmpty()) {
            val noun = if (isTest) "tested class" else "test"
            StatusDisplayer.getDefault().setStatusText("No $noun found for ${fo.nameExt}")
            return
        }

        val best = sourceRelPath?.let { relPath ->
            candidates.firstOrNull { sameRelativePackage(it, roots, relPath) }
        } ?: candidates.first()
        log.logInfo("KotlinGoToTestAction: picked=${best.path}")

        openFile(best)
    }

    private fun openFile(fo: FileObject) {
        runCatching {
            val dataObject = DataObject.find(fo)
            val openCookie = dataObject.getLookup().lookup(OpenCookie::class.java)
            openCookie?.open()
        }.onFailure {
            KotlinLogger.INSTANCE.logException("KotlinGoToTestAction.openFile failed for ${fo.path}", it)
        }
    }

    private fun collectSourceRoots(project: Project): List<FileObject> {
        val sources = org.netbeans.api.project.ProjectUtils.getSources(project)
        val roots = LinkedHashSet<FileObject>()
        sources.getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA).forEach {
            roots += it.rootFolder
        }
        val projectDir = project.projectDirectory
        if (projectDir != null) {
            listOf("src/main/kotlin", "src/test/kotlin").forEach { relPath ->
                projectDir.getFileObject(relPath)?.takeIf { it.isFolder }?.let { roots += it }
            }
        }
        return roots.toList()
    }

    private fun findKtFiles(root: FileObject, targetNames: Collection<String>): List<FileObject> {
        val result = mutableListOf<FileObject>()
        val stack = ArrayDeque<FileObject>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in current.children) {
                if (child.isFolder) {
                    stack.add(child)
                } else if (child.isKotlinFile() && child.name in targetNames) {
                    result += child
                }
            }
        }
        return result
    }

    private fun sourceRootRelativePath(fo: FileObject, roots: List<FileObject>): String? {
        for (root in roots) {
            val rel = FileUtil.getRelativePath(root, fo)
            if (rel != null) return rel
        }
        return null
    }

    private fun sameRelativePackage(
        candidate: FileObject, roots: List<FileObject>, sourceRelPath: String
    ): Boolean {
        for (root in roots) {
            val rel = FileUtil.getRelativePath(root, candidate) ?: continue
            val srcDir = sourceRelPath.substringBeforeLast('/', "")
            val candDir = rel.substringBeforeLast('/', "")
            return srcDir == candDir
        }
        return false
    }

    companion object {
        const val ACTION_NAME = "kotlin-goto-test"
    }
}
