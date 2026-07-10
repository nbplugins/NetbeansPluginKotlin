/*******************************************************************************
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

import org.netbeans.junit.NbTestCase

/**
 * Unit tests for [TextRangeDiff] — the longest-common-prefix/suffix diff used by
 * [KotlinChangeSignatureApplyElement] to replace and reformat only the changed region of a file
 * instead of the whole document.
 */
class TextRangeDiffTest : NbTestCase("TextRangeDiffTest") {

    /** Applies the diff the same way production code does, to verify round-tripping. */
    private fun applyDiff(oldText: String, changed: TextRangeDiff.Changed, newText: String): String =
        oldText.substring(0, changed.oldStart) +
            newText.substring(changed.newStart, changed.newEnd) +
            oldText.substring(changed.oldEnd)

    fun testIdenticalTexts_producesEmptyRange() {
        val text = "fun greet(first: String): String = first\n"
        val changed = TextRangeDiff.compute(text, text)

        assertEquals("no change: oldStart == oldEnd", changed.oldStart, changed.oldEnd)
        assertEquals("no change: newStart == newEnd", changed.newStart, changed.newEnd)
        assertEquals(applyDiff(text, changed, text), text)
    }

    /**
     * Regression for the manual-test finding that whole-file reformatting after Change Signature
     * was too broad: a single rename deep inside a large surrounding function body (unreferenced
     * in the body, so there is exactly *one* point of difference) must produce a changed region
     * much smaller than the whole file, with a large untouched common prefix and suffix.
     */
    fun testParameterRenamedInMiddle_changedRegionIsMinimal() {
        val old = "fun greet(first: String, second: String): String {\n    return \"result: \$second\"\n}\n"
        val new = "fun greet(who: String, second: String): String {\n    return \"result: \$second\"\n}\n"

        val changed = TextRangeDiff.compute(old, new)
        assertEquals("round-trips to the new text", new, applyDiff(old, changed, new))

        // Only "first" -> "who" differs; the common prefix ("fun greet(") and the common suffix
        // (everything from ", second: String)" to the end) must both be recognized as unchanged,
        // leaving a changed region far smaller than the whole ~80-char text.
        assertTrue(
            "expected a small changed region, got oldStart=${changed.oldStart} oldEnd=${changed.oldEnd} in a ${old.length}-char string",
            changed.oldEnd - changed.oldStart < 10,
        )
        assertTrue("changed region must end well before the end of the file", changed.oldEnd < old.length - 30)
    }

    fun testAppendedSuffix_onlyNewRangeNonEmpty() {
        val old = "fun greet(first: String)"
        val new = "fun greet(first: String, second: String)"

        val changed = TextRangeDiff.compute(old, new)
        // Nothing is removed from the old text (pure insertion): the old range is empty. Note
        // this isn't old.length/old.length — the final ")" is common to both texts too, so it
        // joins the common *suffix* rather than the common prefix; the round-trip check below is
        // what actually matters, this just documents that the region is indeed a pure insert.
        assertEquals("pure insertion: old range is empty", changed.oldStart, changed.oldEnd)
        assertEquals(", second: String".length, changed.newEnd - changed.newStart)
        assertEquals(new, applyDiff(old, changed, new))
    }

    fun testPrependedPrefix_onlyNewRangeNonEmpty() {
        val old = "fun greet(): String"
        val new = "public fun greet(): String"

        val changed = TextRangeDiff.compute(old, new)
        assertEquals("nothing removed from the old text", 0, changed.oldStart)
        assertEquals("nothing removed from the old text", 0, changed.oldEnd)
        assertEquals("public ", new.substring(changed.newStart, changed.newEnd))
        assertEquals(new, applyDiff(old, changed, new))
    }

    fun testCompletelyDifferentTexts_wholeRangeChanged() {
        val old = "abc"
        val new = "xyz"

        val changed = TextRangeDiff.compute(old, new)
        assertEquals(0, changed.oldStart)
        assertEquals(3, changed.oldEnd)
        assertEquals(0, changed.newStart)
        assertEquals(3, changed.newEnd)
        assertEquals(new, applyDiff(old, changed, new))
    }

    fun testEmptyOldText_pureInsert() {
        val changed = TextRangeDiff.compute("", "new content")
        assertEquals(0, changed.oldStart)
        assertEquals(0, changed.oldEnd)
        assertEquals("new content", applyDiff("", changed, "new content"))
    }

    fun testEmptyNewText_pureDeletion() {
        val changed = TextRangeDiff.compute("old content", "")
        assertEquals(0, changed.newStart)
        assertEquals(0, changed.newEnd)
        assertEquals("", applyDiff("old content", changed, ""))
    }

    /** Applies [hunks] back-to-front against [oldText], the same order production code uses. */
    private fun applyHunks(oldText: String, hunks: List<TextRangeDiff.Changed>, newText: String): String {
        var result = oldText
        for (hunk in hunks.sortedByDescending { it.oldStart }) {
            result = result.substring(0, hunk.oldStart) + newText.substring(hunk.newStart, hunk.newEnd) + result.substring(hunk.oldEnd)
        }
        return result
    }

    /**
     * Regression: a file with two independent call sites Change Signature updates, and an
     * unrelated, deliberately badly-formatted line between them, must produce **two separate
     * hunks** — one per call site — never a single region spanning across the badly-formatted
     * line. That line must appear character-for-character identical in both texts and fall
     * strictly *between* the two hunks (untouched by either).
     */
    fun testComputeHunks_twoDistantCallSites_leavesUnrelatedMiddleLineUntouched() {
        val middleLine = "   val   weird =    1    // deliberately odd formatting, must survive untouched\n"
        val old = "fun useA(): String = greet(\"alpha\")\n" +
            middleLine +
            "fun useB(): String = greet(\"beta\")\n"
        val new = "fun useA(): String = greet(\"alpha\", second)\n" +
            middleLine +
            "fun useB(): String = greet(\"beta\", second)\n"

        val hunks = TextRangeDiff.computeHunks(old, new)
        assertEquals("expected exactly one hunk per call site, got: $hunks", 2, hunks.size)

        assertEquals("round-trips to the new text", new, applyHunks(old, hunks, new))

        val middleStart = old.indexOf(middleLine)
        val middleEnd = middleStart + middleLine.length
        for (hunk in hunks) {
            assertTrue(
                "hunk $hunk must not overlap the untouched middle line [$middleStart, $middleEnd)",
                hunk.oldEnd <= middleStart || hunk.oldStart >= middleEnd,
            )
        }
    }

    /** Identical texts produce no hunks at all. */
    fun testComputeHunks_identicalTexts_producesNoHunks() {
        val text = "fun f() {\n    println(1)\n}\n"
        assertEquals(emptyList<TextRangeDiff.Changed>(), TextRangeDiff.computeHunks(text, text))
    }

    /** A single isolated change still produces exactly one hunk, matching [compute]'s result. */
    fun testComputeHunks_singleChange_producesOneHunkMatchingCompute() {
        val old = "fun greet(first: String): String = \"hi\"\n"
        val new = "fun greet(who: String): String = \"hi\"\n"

        val hunks = TextRangeDiff.computeHunks(old, new)
        assertEquals(1, hunks.size)
        assertEquals(new, applyHunks(old, hunks, new))
    }
}
