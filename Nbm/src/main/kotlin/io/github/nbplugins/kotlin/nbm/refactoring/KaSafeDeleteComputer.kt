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
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.netbeans.modules.csl.api.OffsetRange
import org.openide.filesystems.FileObject

/**
 * Result of a [KaSafeDeleteComputer] analysis.
 *
 * @param declarationFilePath virtual-file path of the file containing the declaration
 * @param declarationStart    start offset of the declaration (inclusive; covers modifiers + KDoc anchor)
 * @param declarationEnd      end offset of the declaration (exclusive), extended to consume the trailing newline
 * @param declarationName     simple name of the symbol being deleted
 * @param usages              map from each file that references the symbol to the list of reference ranges
 */
data class KaSafeDeleteResult(
    val declarationFilePath: String,
    val declarationStart: Int,
    val declarationEnd: Int,
    val declarationName: String,
    val usages: Map<FileObject, List<OffsetRange>>,
)

/**
 * Computes the information needed to perform a Safe Delete refactoring on a Kotlin symbol.
 *
 * Given a cursor position, this class:
 * 1. Resolves the [KtNamedDeclaration] at the cursor.
 * 2. Computes the text range to delete (the full declaration, plus its trailing newline).
 * 3. Uses [KaFindUsagesComputer] to find all references to the symbol across [allFiles].
 *
 * If there are no usages, deletion is safe. If there are usages, the caller should warn
 * the user and require confirmation before proceeding.
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 *
 * @param cursorKtFile the K2-session KtFile at the cursor (must be registered with [session])
 * @param offset       character offset of the cursor within [cursorKtFile]
 * @param session      the active [KotlinAnalysisAPISession] for the project
 * @param allFiles     all project source [FileObject]s to search for references
 */
class KaSafeDeleteComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
    private val session: KotlinAnalysisAPISession,
    private val allFiles: Set<FileObject>,
) {

    /**
     * Runs the safe-delete analysis.
     *
     * @return a [KaSafeDeleteResult] describing the declaration range and all found usages,
     *         or `null` if no resolvable named declaration exists at [offset]
     */
    fun compute(): KaSafeDeleteResult? {
        val element = cursorKtFile.findElementAt(offset) ?: return null
        val declaration = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)
            ?: return null
        val name = declaration.name ?: return null
        val filePath = cursorKtFile.virtualFile?.path ?: return null

        val psiRange = declaration.textRange
        val fileText = cursorKtFile.text

        // Expand the start backwards to include any preceding blank lines so the
        // deleted region doesn't leave a spurious blank line behind.
        val expandedStart = expandStartOverBlankLines(fileText, psiRange.startOffset)

        // Expand the end to consume the trailing newline (if any).
        val expandedEnd = if (psiRange.endOffset < fileText.length && fileText[psiRange.endOffset] == '\n')
            psiRange.endOffset + 1
        else
            psiRange.endOffset

        val usages = KaFindUsagesComputer(cursorKtFile, offset, session, allFiles).compute()

        return KaSafeDeleteResult(filePath, expandedStart, expandedEnd, name, usages)
    }

    /**
     * Walks backwards from [start] to include any preceding blank lines in the deletion range.
     *
     * A "blank line" is a line consisting only of whitespace. This prevents leaving an extra
     * empty line when a declaration is the only thing between two blank lines.
     *
     * Only blank lines immediately before the declaration are consumed; lines containing code
     * stop the walk.
     *
     * @param text  full source text
     * @param start the original start offset of the declaration
     * @return the adjusted start offset (≤ [start])
     */
    private fun expandStartOverBlankLines(text: String, start: Int): Int {
        var pos = start
        // Walk backward through whitespace-only lines.
        while (pos > 0) {
            // Find the start of the line immediately before pos.
            val lineStart = text.lastIndexOf('\n', pos - 1) + 1  // +1 skips the '\n' itself
            val lineContent = text.substring(lineStart, pos)
            if (lineContent.isBlank()) {
                // This line is blank — include it in the deletion range.
                pos = lineStart
                // Also consume the '\n' that ends the previous line.
                if (pos > 0 && text[pos - 1] == '\n') pos--
            } else {
                // Non-blank line found — stop.
                break
            }
        }
        // Restore the leading newline that precedes the declaration so the remaining
        // code above retains its trailing newline.
        return if (pos < start && pos < text.length && text[pos] == '\n') pos + 1 else pos
    }
}
