/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Detects the indent step near [offset] by looking at [windowLines] lines before and after it.
 *
 * Collects the leading-whitespace lengths of non-empty lines in the window, sorts the unique
 * values, and returns the minimum positive difference between adjacent levels — that is the
 * actual indent step used around this position. Falls back to 4 spaces when no difference can
 * be determined. Tab-indented files are detected by the presence of a leading tab and return
 * `"\t"`.
 */
fun detectIndentStep(docText: String, offset: Int, windowLines: Int = 10): String {
    // Find line boundaries of the window
    var lineStart = offset
    while (lineStart > 0 && docText[lineStart - 1] != '\n') lineStart--
    var start = lineStart
    repeat(windowLines) { if (start > 0) start = docText.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 } }
    var end = offset
    repeat(windowLines) { end = docText.indexOf('\n', end + 1).let { if (it < 0) docText.length else it } }

    val indents = mutableSetOf<Int>()
    var useTab = false
    for (line in docText.substring(start, end).lineSequence()) {
        if (line.isBlank()) continue
        if (line.startsWith("\t")) { useTab = true; break }
        val w = line.length - line.trimStart().length
        if (w > 0) indents.add(w)
    }
    if (useTab) return "\t"
    val sorted = indents.sorted()
    val step = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull() ?: sorted.firstOrNull() ?: 4
    return " ".repeat(step)
}

/**
 * Returns the PSI anchor for inserting / removing a type annotation on [element].
 *
 * This mirrors [org.jetbrains.kotlin.hints.intentions.getAnchor] for use in the K2 path.
 *
 * @param element the callable declaration whose type annotation position is requested
 * @return the anchor element, or null if the declaration kind is unsupported
 */
fun getAnchorK2(element: KtCallableDeclaration): PsiElement? = when (element) {
    is KtProperty, is KtParameter -> element.nameIdentifier
    is KtNamedFunction -> element.valueParameterList
    else -> null
}

/**
 * Applies a PSI-mutating transformation to a copy of the document and writes the result back.
 *
 * Creates a non-physical [KtFile] copy of the current document text, finds the target element
 * in the copy by the element's text offset, calls [transform] on it, then replaces the
 * element's range in [doc] with the new text from the (possibly mutated) copy element.
 *
 * @param T the PSI element type to find and transform
 * @param doc the NetBeans Swing document to update
 * @param kaKtFile the K2-session-owned file (provides project context and element offset)
 * @param element the PSI element to transform; must belong to [kaKtFile]
 * @param transform mutation function; receives the copy element and returns the resulting PSI
 *        element whose text will replace the original range (may be a parent if the mutation
 *        replaces the element entirely)
 */
inline fun <reified T : PsiElement> applyPsiTransform(
    doc: Document,
    kaKtFile: KtFile,
    element: T,
    transform: (T) -> PsiElement?,
) {
    val startOffset = element.textRange.startOffset
    val endOffset = element.textRange.endOffset
    val factory = KtPsiFactory(kaKtFile.project)
    val copyFile = factory.createFile(doc.getText(0, doc.length))
    val anchor = copyFile.findElementAt(startOffset)
        ?: copyFile.findElementAt(startOffset + 1)
        ?: return
    val copyElement = PsiTreeUtil.getParentOfType(anchor, T::class.java, false) ?: return
    val result = transform(copyElement) ?: return
    val newText = result.text
    doc.atomicChange {
        remove(startOffset, endOffset - startOffset)
        insertString(startOffset, newText, null)
    }
}
