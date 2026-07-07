/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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


package io.github.nbplugins.kotlin.nbm.indentation

import javaproject.JavaProject
import javax.swing.text.AttributeSet
import javax.swing.text.Document
import javax.swing.text.DocumentFilter
import javax.swing.text.PlainDocument
import javax.swing.text.StyledDocument
import org.jetbrains.kotlin.formatting.KotlinIndentStrategy
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.indentation.applyDeltaToText
import io.github.nbplugins.kotlin.nbm.indentation.computePasteAdjustment
import io.github.nbplugins.kotlin.nbm.indentation.indentWidth
import io.github.nbplugins.kotlin.nbm.indentation.PasteAdjustmentSuppressor
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

    fun testEnterBetweenBracesInClassMethod() = doTest("enterBetweenBracesInClassMethod.kt")

    fun testEnterBetweenBracesDeepNesting() = doTest("enterBetweenBracesDeepNesting.kt")

    /**
     * Regression test: [org.jetbrains.kotlin.formatting.KotlinIndentStrategy.autoEditAfterOpenBraceAndBeforeCloseBrace]
     * hardcodes a literal 4-space step for the content line instead of using the project's
     * configured `INDENT_SIZE`. With a 2-space indent configured, the new content line
     * inside `fun f() {<caret>}` must get exactly 2 spaces, not 4.
     */
    fun testEnterBetweenBracesRespectsConfiguredIndentSize() {
        val twoSpaceSettings = KotlinFormatterUtils.getSettings().clone()
        twoSpaceSettings.getIndentOptions()?.apply {
            INDENT_SIZE = 2
            TAB_SIZE = 2
            CONTINUATION_INDENT_SIZE = 2
        }
        KotlinFormatterUtils.pushSettings(twoSpaceSettings)
        try {
            doTest("enterBetweenBracesTwoSpaceIndent.kt")
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }

/**
     * Regression test for the real bug: [KotlinPasteIndentFilter] intercepts every
     * [javax.swing.text.Document] insert — including [KotlinIndentStrategy]'s own Enter-key
     * edit, not just genuine user pastes — and, whenever its own recomputed indent disagrees
     * with the width already present on the inserted text, rewrites that text via
     * [applyDeltaToText] (which collapses whitespace-only lines to empty). This corrupted a
     * live user's file: the formatter-computed indent for the *content* line of an
     * Enter-in-`{}` edit and the filter's independently recomputed "paste" indent disagreed,
     * so the filter zeroed out the indentation [KotlinIndentStrategy] had just computed
     * correctly. [PasteAdjustmentSuppressor] fixes this by having [KotlinIndentStrategy] mark
     * its own edits so the filter passes them through unchanged.
     *
     * The project-backed test [StyledDocument] returned by `getDocumentForFileObject` is not
     * an [javax.swing.text.AbstractDocument], so it can't have a real
     * [javax.swing.text.DocumentFilter] installed on it (unlike a real editor document) —
     * these tests instead drive [KotlinPasteIndentFilter] directly with a hand-rolled
     * [DocumentFilter.FilterBypass] that forwards to the same document, exercising the exact
     * interception logic that runs in production. The inserted text's indent is deliberately
     * set to disagree with the file's real 2-space indent, guaranteeing the filter's
     * paste-adjustment would trigger if not suppressed.
     */
    fun testPasteFilterDoesNotMangleSuppressedEdit() {
        val (doc, offset, fb, filter) = setUpPasteFilterScenario("enterBetweenBracesRealRepro.kt")

        // Deliberately mismatched vs. the file's actual 2-space indent, so an unsuppressed
        // filter would recompute and rewrite it.
        val textToInsert = "        \n    "

        PasteAdjustmentSuppressor.begin()
        try {
            filter.insertString(fb, offset, textToInsert, null)
        } finally {
            PasteAdjustmentSuppressor.end()
        }

        assertEquals(textToInsert, doc.getText(offset, textToInsert.length))
    }

    /** Without suppression, the same mismatched-indent insert is rewritten by the filter — proves the test above is exercising real interception, not a no-op. */
    fun testPasteFilterManglesUnsuppressedEdit() {
        val (doc, offset, fb, filter) = setUpPasteFilterScenario("enterBetweenBracesRealReproMangle.kt")

        val textToInsert = "        \n    "

        filter.insertString(fb, offset, textToInsert, null)

        val actual = doc.getText(offset, minOf(textToInsert.length, doc.length - offset))
        assertTrue(actual != textToInsert)
    }

    private data class PasteFilterScenario(
        val doc: StyledDocument,
        val offset: Int,
        val fb: DocumentFilter.FilterBypass,
        val filter: KotlinPasteIndentFilter
    )

    private fun setUpPasteFilterScenario(fileName: String): PasteFilterScenario {
        val doc = getDocumentForFileObject(dir, fileName) as StyledDocument
        val offset = getCaret(doc) + 1
        doc.remove(offset - 1, "<caret>".length)
        doc.insertString(offset - 1, "\n", null)

        val fb = object : DocumentFilter.FilterBypass() {
            override fun getDocument(): Document = doc
            override fun remove(offset: Int, length: Int) = doc.remove(offset, length)
            override fun insertString(offset: Int, string: String?, attrs: AttributeSet?) = doc.insertString(offset, string, attrs)
            override fun replace(offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                doc.remove(offset, length)
                doc.insertString(offset, text, attrs)
            }
        }
        return PasteFilterScenario(doc, offset, fb, KotlinPasteIndentFilter(null))
    }
    
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
