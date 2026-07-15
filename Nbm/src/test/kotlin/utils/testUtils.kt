/*******************************************************************************
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
 *******************************************************************************/
package utils

import com.intellij.openapi.util.text.StringUtil
import javax.swing.text.Document
import junit.framework.TestCase.assertNotNull
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject

fun getCaret(doc: Document): Int = doc.getText(0,doc.length).indexOf("<caret>")

fun Document.carets(): List<Int> {
    val result = arrayListOf<Int>()
    val caret = "<caret>"
    val text = getText(0, length)
    
    var index = 0
    
    while(true) {
        val newIndex = text.substring(index).indexOf(caret)
        if (newIndex == -1) break
        
        index += newIndex
        result.add(index)
        
        index += caret.length
    }
    
    return result.mapIndexed { i, it -> it - caret.length * i}
}

/**
 * Loads the document for [fo] without opening a visible editor pane.
 *
 * Unlike [org.jetbrains.kotlin.utils.ProjectUtils.getDocumentFromFileObject] (which also calls
 * [EditorCookie.open] for real navigation features), this only loads the [Document] content via
 * [EditorCookie.openDocument]. Tests never need the pane itself, and opening it races on the AWT
 * tree lock / CloneableOpenSupport listener lock against production code under test that also
 * opens a pane for the same file (e.g. KotlinIndentStrategy's caret-repositioning `Line.show()`),
 * producing an AB-BA deadlock between the test thread and the real AWT event thread.
 */
fun getDocumentForFileObject(fo: FileObject): Document {
    val dataObject = DataObject.find(fo)
    val editorCookie = dataObject.lookup.lookup(EditorCookie::class.java)
    assertNotNull(editorCookie)
    return editorCookie!!.openDocument()
}

fun getDocumentForFileObject(dir: FileObject, fileName: String): Document {
    val file = dir.getFileObject(fileName)

    assertNotNull(file)

    return getDocumentForFileObject(file)
}

fun getAllKtFilesInFolder(folder: FileObject) = folder.children.filter{ it.hasExt("kt") }

infix fun String.equalsWithoutSpaces(expected: String): Boolean {
    val spacesRegex = Regex("\\s+")
    
    return spacesRegex.replace(StringUtil.convertLineSeparators(expected), "") == 
        spacesRegex.replace(StringUtil.convertLineSeparators(this), "")
}