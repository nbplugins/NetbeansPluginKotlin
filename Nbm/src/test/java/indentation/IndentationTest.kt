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


package indentation

import javaproject.JavaProject
import javax.swing.text.PlainDocument
import javax.swing.text.StyledDocument
import org.jetbrains.kotlin.formatting.KotlinIndentStrategy
import org.jetbrains.kotlin.indentation.applyDeltaToText
import org.jetbrains.kotlin.indentation.computePasteAdjustment
import org.jetbrains.kotlin.indentation.indentWidth
import org.netbeans.api.project.Project
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import utils.*

class IndentationTest : KotlinTestCase("Indentation test", "indentation") {

    fun doTest(fileName: String) {
        val doc = getDocumentForFileObject(dir, fileName) as StyledDocument
        val offset = getCaret(doc) + 1
        doc.remove(offset - 1, "<caret>".length)
        doc.insertString(offset - 1, "\n", null)
        
        val strategy = KotlinIndentStrategy(doc, offset)
        val newOffset = strategy.addIndent()
        
        val doc2 = getDocumentForFileObject(dir, fileName.replace(".kt", ".after"))
        val expectedOffset = getCaret(doc2)
        
        assertEquals(expectedOffset, newOffset)
    }
    
    fun testAfterOneOpenBrace() = doTest("afterOneOpenBrace.kt")
    
    fun testBeforeFunctionStart() = doTest("beforeFunctionStart.kt")
    
    fun testBetweenBracesOnDifferentLines() = doTest("betweenBracesOnDifferentLine.kt")
    
    fun testBreakLineAfterIfWithoutBraces() = doTest("breakLineAfterIfWithoutBraces.kt")
    
    fun testAfterOperatorIfWithoutBraces() = doTest("afterOperatorIfWithoutBraces.kt")
    
    fun testAfterOperatorWhileWithoutBraces() = doTest("afterOperatorWhileWithoutBraces.kt")
    
    fun testBeforeCloseBrace() = doTest("beforeCloseBrace.kt")
    
    fun testContinuationAfterDotCall() = doTest("continuationAfterDotCall.kt")
    
    fun testContinuationBeforeFunName() = doTest("continuationBeforeFunName.kt")
    
    fun testBeforeNestedCloseBrace() = doTest("beforeNestedCloseBrace.kt")
    
    fun testBeforeTwiceNestedCloseBrace() = doTest("beforeTwiceNestedCloseBrace.kt")
    
    fun testAfterEquals() = doTest("afterEquals.kt")
    
    fun testIndentBeforeWhile() = doTest("indentBeforeWhile.kt")
    
    fun testLineBreakSaveIndent() = doTest("lineBreakSaveIndent.kt")
    
    fun testNestedOperatorsWithBraces() = doTest("nestedOperatorsWithBraces.kt")
    
    fun testNestedOperatorsWithoutBraces() = doTest("nestedOperatorsWithoutBraces.kt")
    
    fun testNewLineInParameters() = doTest("newLineInParameters.kt")
    
    fun testNewLineWhenCaretAtPosition0() = doTest("newLineWhenCaretAtPosition0.kt")
    
//    fun testBetweenBracesOnOneLine() = doTest("betweenBracesOnOneLine.kt")
//
//    fun testBetweenBracesOnOneLine2() = doTest("betweenBracesOnOneLine2.kt")

    // ----- end-to-end paste tests (real project document, formatter active) -----

    /**
     * Simulates pasting [pastedText] at the `<caret>` position in [fileName] through the
     * production [computePasteAdjustment] path (formatter-backed indent computation), then
     * compares the resulting document to the corresponding `.after` fixture.
     */
    fun doPasteTest(fileName: String, pastedText: String) {
        val doc = getDocumentForFileObject(dir, fileName) as StyledDocument
        val caret = getCaret(doc)
        doc.remove(caret, "<caret>".length)

        val adjusted = computePasteAdjustment(doc, caret, pastedText)
        doc.insertString(caret, adjusted ?: pastedText, null)

        val expected = getDocumentForFileObject(dir, fileName.replace(".kt", ".after"))
        assertEquals(expected.getText(0, expected.length), doc.getText(0, doc.getLength()))
    }

    /** Paste two zero-indent lines onto a blank line inside a function body → shifted to body level. */
    fun testPasteAtBlankLineInBody() =
        doPasteTest("pasteAtBlankLineInBody.kt", "val a = 1\nval b = 2")

    /** Paste onto a blank line inside a nested `if` block → shifted to the nested level. */
    fun testPasteInNestedIf() =
        doPasteTest("pasteInNestedIf.kt", "val a = 1\nval b = 2")

    /** Paste a block that carries its own relative indent → whole block shifted, structure preserved. */
    fun testPasteWithOwnIndent() =
        doPasteTest("pasteWithOwnIndent.kt", "if (x) {\n    doThing()\n}")

    /** Paste a single line (no newline) onto a blank line inside a function body → indented to body level. */
    fun testPasteSingleLineAtBlankLineInBody() =
        doPasteTest("pasteSingleLineAtBlankLineInBody.kt", "val x = 1")

    // ----- applyDeltaToText tests (KotlinPasteIndentFilter) -----

    /** Positive delta shifts all non-blank lines right; blank lines become empty. */
    fun testApplyDeltaToTextPositive() {
        val text = "val x = 1\n\nval y = 2\n"
        assertEquals("    val x = 1\n\n    val y = 2\n", applyDeltaToText(text, 4))
    }

    /** Negative delta shifts lines left; indent cannot go below zero. */
    fun testApplyDeltaToTextNegative() {
        val text = "    val a = 1\n    val b = 2\n"
        assertEquals("val a = 1\nval b = 2\n", applyDeltaToText(text, -4))
    }

    /** Tab counted as 8 spaces; output is spaces only. */
    fun testApplyDeltaToTextTabToSpaces() {
        val text = "\tval x = 1\n"
        assertEquals("val x = 1\n", applyDeltaToText(text, -8))
    }

    /** Lines with only whitespace are cleared regardless of delta. */
    fun testApplyDeltaToTextBlankLinesCleared() {
        val text = "val a = 1\n   \nval b = 2\n"
        assertEquals("  val a = 1\n\n  val b = 2\n", applyDeltaToText(text, 2))
    }

}
