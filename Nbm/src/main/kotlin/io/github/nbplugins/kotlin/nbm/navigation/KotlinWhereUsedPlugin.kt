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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.api.WhereUsedQuery
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.text.PositionBounds
import org.openide.text.PositionRef
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that handles [WhereUsedQuery] for Kotlin source files.
 *
 * When the user invokes Alt+F7 (Find Usages) on a Kotlin symbol, [prepare] is called by the
 * NetBeans refactoring infrastructure. This plugin:
 * 1. Extracts the cursor [FileObject] and offset from [refactoring]'s source lookup.
 * 2. Resolves the symbol at the cursor using [KaFindUsagesComputer].
 * 3. Adds a [KotlinFindUsagesResultElement] to [bag] for each found reference.
 *
 * This class belongs to the **controller** layer: it wires the K2 model ([KaFindUsagesComputer])
 * to the NetBeans refactoring view ([RefactoringElementsBag]).
 *
 * @param refactoring the [WhereUsedQuery] created by [KotlinWhereUsedRefactoringUI]
 */
class KotlinWhereUsedPlugin(private val refactoring: WhereUsedQuery) :
    ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Searches all Kotlin source files in the project for references to the symbol at the cursor,
     * then adds a [KotlinFindUsagesResultElement] for each reference to [bag].
     *
     * @param bag the bag to add found usage elements to
     * @return `null` (no problem), or a [Problem] if required context is missing
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.refactoringSource.lookup(StyledDocument::class.java)
            ?: return null
        val fo = ProjectUtils.getFileObjectForDocument(doc)
            ?: return null
        val offset = refactoring.refactoringSource.lookup(Int::class.javaObjectType)
            ?: return null

        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: return null

        val session = KotlinAnalysisAPISession.getSession(project)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val allFiles = KotlinPsiManager.getFilesByProject(project)
        val usages = KaFindUsagesComputer(ktFile, offset, session, allFiles).compute()

        for ((fileObject, ranges) in usages) {
            for (range in ranges) {
                runCatching {
                    val element = KotlinFindUsagesResultElement(range, fileObject)
                    bag.add(refactoring, element)
                }.onFailure { e ->
                    KotlinLogger.INSTANCE.logException(
                        "KotlinWhereUsedPlugin: failed to add result element for $fileObject", e
                    )
                }
            }
        }

        return null
    }
}

/**
 * A single find-usages result: one reference occurrence in [parentFile] at [range].
 *
 * Implements [SimpleRefactoringElementImplementation] for display in the Find Usages panel.
 * The element is read-only ([performChange] is a no-op).
 *
 * @param range the character offset range of the reference expression
 * @param parentFile the file containing the reference
 */
class KotlinFindUsagesResultElement(
    private val range: OffsetRange,
    private val parentFile: FileObject
) : SimpleRefactoringElementImplementation() {

    /** Find Usages is a read-only query; no changes are performed. */
    override fun performChange() {}

    override fun getLookup(): Lookup = Lookups.fixed(parentFile)

    override fun getParentFile(): FileObject = parentFile

    /**
     * Computes [PositionBounds] for the usage range so NetBeans can navigate to it.
     *
     * Returns `null` if the editor support is unavailable for the file.
     */
    override fun getPosition(): PositionBounds? = try {
        val dob = org.openide.loaders.DataObject.find(parentFile)
        val ces = dob.lookup.lookup(org.openide.text.CloneableEditorSupport::class.java)
            ?: return null
        val start: PositionRef = ces.createPositionRef(range.start, Bias.Forward)
        val end: PositionRef = ces.createPositionRef(range.end, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    override fun getText(): String = buildLineText()

    override fun getDisplayText(): String = buildLineText()

    private fun buildLineText(): String {
        return try {
            val dob = org.openide.loaders.DataObject.find(parentFile)
            val ec = dob.lookup.lookup(org.openide.cookies.EditorCookie::class.java) ?: return ""
            val doc = ec.openDocument() ?: return ""
            val text = doc.getText(0, doc.length)
            val lineStart = text.lastIndexOf('\n', range.start - 1) + 1
            val lineEnd = text.indexOf('\n', range.end).takeIf { it >= 0 } ?: text.length
            text.substring(lineStart, lineEnd).trim()
        } catch (_: Exception) { "" }
    }
}
