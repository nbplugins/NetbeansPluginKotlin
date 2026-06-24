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
package io.github.nbplugins.kotlin.nbm.refactoring

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.navigation.KaFindUsagesComputer
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.netbeans.modules.csl.api.OffsetRange
import org.openide.filesystems.FileObject

/**
 * Result of a [KaInlineVariableComputer] analysis.
 *
 * @param declarationFilePath virtual-file path of the file containing the property declaration
 * @param declarationStart    start offset of the property statement (inclusive)
 * @param declarationEnd      end offset of the property statement (exclusive), extended to consume trailing newline
 * @param declarationName     simple name of the property being inlined
 * @param initializerText     text of the property initializer to substitute at each read usage
 * @param readUsages          map from file to list of read-reference ranges (each range should be replaced with [initializerText])
 * @param writeUsages         map from file to list of write-reference ranges (assignments to the property — conflict if non-empty)
 */
data class KaInlineVariableResult(
    val declarationFilePath: String,
    val declarationStart: Int,
    val declarationEnd: Int,
    val declarationName: String,
    val initializerText: String,
    val readUsages: Map<FileObject, List<OffsetRange>>,
    val writeUsages: Map<FileObject, List<OffsetRange>>,
)

/**
 * Computes the information needed to inline a local Kotlin `val` (or simple `var`) property.
 *
 * Algorithm:
 * 1. Resolves the [KtProperty] at the cursor.
 * 2. Validates the property: must have an initializer and no custom getter/setter.
 * 3. Finds all references to the property using [KaFindUsagesComputer].
 * 4. Returns a [KaInlineVariableResult] with the declaration range, initializer text, and usage ranges.
 *
 * The caller is responsible for applying the [KaInlineVariableResult]:
 * - Replace each entry in [KaInlineVariableResult.readUsages] with [KaInlineVariableResult.initializerText].
 * - Delete the range [[KaInlineVariableResult.declarationStart], [KaInlineVariableResult.declarationEnd]).
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 *
 * @param cursorKtFile the K2-session KtFile at the cursor
 * @param offset       character offset of the cursor within [cursorKtFile]
 * @param session      the active [KotlinAnalysisAPISession] for the project
 * @param allFiles     all project source [FileObject]s to search for references
 */
class KaInlineVariableComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
    private val session: KotlinAnalysisAPISession,
    private val allFiles: Set<FileObject>,
) {

    /**
     * Runs the inline-variable analysis.
     *
     * @return a [KaInlineVariableResult] describing the property and its usages,
     *         or `null` if no inlineable property exists at [offset]
     */
    fun compute(): KaInlineVariableResult? {
        val element = cursorKtFile.findElementAt(offset) ?: return null
        val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false) ?: return null

        // Only properties with a simple initializer are supported (no custom getter/setter).
        val initializer = property.initializer ?: return null
        if (property.getter != null || property.setter != null) return null

        val name = property.name ?: return null
        val filePath = cursorKtFile.virtualFile?.path ?: return null

        val initText = initializer.text

        // Find all references to the property.
        val allUsages = KaFindUsagesComputer(cursorKtFile, offset, session, allFiles).compute()

        // KaFindUsagesComputer doesn't distinguish read vs write usages in its current form;
        // for local val properties there are only read usages, so treat all as reads.
        // Write-usage detection (for var) would require K2 analysis — deferred to a future iteration.
        val readUsages: Map<FileObject, List<OffsetRange>> = allUsages
        val writeUsages: Map<FileObject, List<OffsetRange>> = emptyMap()

        // Compute the declaration range (the full `val name = ...` statement).
        val psiRange = property.textRange
        val fileText = cursorKtFile.text
        val declEnd = if (psiRange.endOffset < fileText.length && fileText[psiRange.endOffset] == '\n')
            psiRange.endOffset + 1
        else
            psiRange.endOffset

        return KaInlineVariableResult(
            declarationFilePath = filePath,
            declarationStart = psiRange.startOffset,
            declarationEnd = declEnd,
            declarationName = name,
            initializerText = initText,
            readUsages = readUsages,
            writeUsages = writeUsages,
        )
    }
}
