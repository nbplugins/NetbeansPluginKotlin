/**
 * *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 ******************************************************************************
 */
package org.jetbrains.kotlin.navigation.netbeans

import io.github.nbplugins.kotlin.nbm.navigation.KaNavigationUtils
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.netbeans.api.project.Project
import com.intellij.psi.PsiElement
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import java.io.File
import org.jetbrains.kotlin.navigation.*
import org.jetbrains.kotlin.utils.ProjectUtils
import org.jetbrains.kotlin.utils.LineEndUtil
import org.jetbrains.kotlin.psi.KtElement
import javax.swing.text.StyledDocument
import org.netbeans.modules.editor.NbEditorUtilities
import org.openide.loaders.DataObject
import org.openide.text.NbDocument
import org.openide.text.Line
import javax.swing.text.Document

/**
 * Navigates to the declaration referenced by [referenceExpression], using the K2 Analysis API.
 *
 * @return a [Pair] of (document, offset) for the declaration, or null if navigation is not possible
 */
fun navigate(referenceExpression: KtReferenceExpression, project: Project, file: FileObject): Pair<Document, Int>? {
    val k2Psi = KaNavigationUtils.resolveToSourcePsi(referenceExpression, project, file)
        ?: return null
    return gotoKotlinDeclaration(k2Psi, referenceExpression, file)
}

private fun gotoKotlinDeclaration(psi: PsiElement, fromElement: KtElement,
                                  currentFile: FileObject): Pair<Document, Int>? {
    val declarationFile = findFileObjectForReferencedElement(psi, fromElement, currentFile) ?: return null
    val document = ProjectUtils.getDocumentFromFileObject(declarationFile) ?: return null

    val startOffset = LineEndUtil.convertCrToDocumentOffset(psi.containingFile.text, psi.textOffset)
    openFileAtOffset(document, startOffset)
    return Pair(document, startOffset)
}

private fun findFileObjectForReferencedElement(psi: PsiElement, fromElement: KtElement,
                                               currentFile: FileObject): FileObject? {
    if (fromElement.containingFile == psi.containingFile) return currentFile

    val virtualFile = psi.containingFile.virtualFile ?: return null
    var file = FileUtil.toFileObject(File(virtualFile.path))
    if (file != null) return file

    file = getFileObjectFromJar(virtualFile.path) ?: return null
    return file
}

fun FileObject.openFileAtPosition(lineNumber: Int, columnNumber: Int) {
    val dataObject = DataObject.find(this) ?: return
    val editorCookie = dataObject.lookup.lookup(EditorCookie::class.java) ?: return

    if (lineNumber == -1 || lineNumber == 0) editorCookie.open()

    editorCookie.openDocument()
    val line = editorCookie.lineSet.getOriginal(lineNumber - 1)
    if (!line.isDeleted) {
        line.show(Line.ShowOpenType.REUSE, Line.ShowVisibilityType.FOCUS, columnNumber)
    }
}

fun openFileAtOffset(doc: StyledDocument, offset: Int) {
    val line = NbEditorUtilities.getLine(doc, offset, false)
    val colNumber = NbDocument.findLineColumn(doc, offset)
    line.show(Line.ShowOpenType.OPEN, Line.ShowVisibilityType.FRONT, colNumber)
}

fun moveCaretToOffset(doc: StyledDocument, offset: Int) {
    val line = NbEditorUtilities.getLine(doc, offset, false)
    val colNumber = NbDocument.findLineColumn(doc, offset)
    line.show(Line.ShowOpenType.NONE, Line.ShowVisibilityType.NONE, colNumber)
}
