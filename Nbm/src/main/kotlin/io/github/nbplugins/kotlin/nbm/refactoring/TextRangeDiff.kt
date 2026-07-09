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

/**
 * Diffs two whole-file text snapshots down to the minimal changed region, for refactorings (like
 * Change Signature) whose engine returns a full new file text per touched file rather than precise
 * edit ranges.
 *
 * Used instead of a whole-document `remove(0, length)` + `insertString(0, newText)` so that (a) the
 * editor's native Undo reverses one small edit and keeps the caret near the edit site, matching
 * [MinimalDocumentEdits]'s rationale, and (b) the post-edit code-style reformat pass only touches
 * the changed region instead of re-flowing (and needlessly diffing against version control) the
 * whole file.
 */
internal object TextRangeDiff {

    /**
     * The minimal region that differs between the two texts: `old[oldStart, oldEnd)` is the part
     * of the old text that must be removed, and `new[newStart, newEnd)` is what replaces it.
     * Both ranges are empty (`start == end`) when the texts are identical.
     */
    data class Changed(
        val oldStart: Int,
        val oldEnd: Int,
        val newStart: Int,
        val newEnd: Int,
    )

    /** Computes [Changed] by longest-common-prefix / longest-common-suffix (no interior diffing). */
    fun compute(oldText: String, newText: String): Changed {
        val maxCommon = minOf(oldText.length, newText.length)

        var prefixLen = 0
        while (prefixLen < maxCommon && oldText[prefixLen] == newText[prefixLen]) prefixLen++

        var suffixLen = 0
        val maxSuffix = maxCommon - prefixLen
        while (suffixLen < maxSuffix &&
            oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
        ) suffixLen++

        return Changed(
            oldStart = prefixLen,
            oldEnd = oldText.length - suffixLen,
            newStart = prefixLen,
            newEnd = newText.length - suffixLen,
        )
    }

    /**
     * Diffs [oldText] against [newText] as a **vector of small, disjoint hunks** (line-granular,
     * refined to the smallest changed character span within each changed line-block via [compute])
     * instead of one contiguous region spanning from the first to the last difference.
     *
     * This matters when a file has multiple, independent edit points far apart (e.g. two call
     * sites Change Signature updates): [compute]'s single-region result would span everything
     * between them, so *unrelated, already-odd formatting in the untouched text between the two
     * call sites* would get swept into the "changed" region and reformatted/rewritten along with
     * it. [computeHunks] instead returns one [Changed] per independently-changed line-block, each
     * applied and reformatted on its own — every line outside a hunk (including badly-formatted
     * ones between two hunks) is left completely untouched, matching the file's actual edit shape.
     *
     * Falls back to a single [compute] hunk for pathologically large files (more than
     * [MAX_LINES_FOR_LINE_DIFF] lines) where the O(lines²) line-alignment table would be too slow.
     */
    fun computeHunks(oldText: String, newText: String): List<Changed> {
        if (oldText == newText) return emptyList()

        val oldLines = splitKeepingTerminators(oldText)
        val newLines = splitKeepingTerminators(newText)
        if (oldLines.size > MAX_LINES_FOR_LINE_DIFF || newLines.size > MAX_LINES_FOR_LINE_DIFF) {
            return listOf(compute(oldText, newText))
        }

        val oldOffsets = cumulativeOffsets(oldLines)
        val newOffsets = cumulativeOffsets(newLines)
        val matches = lcsLineMatches(oldLines, newLines)

        val hunks = mutableListOf<Changed>()
        var prevOld = 0
        var prevNew = 0
        for ((matchedOld, matchedNew) in matches) {
            if (matchedOld > prevOld || matchedNew > prevNew) {
                hunks += lineHunkTrimmedToChars(oldText, newText, oldOffsets, newOffsets, prevOld, matchedOld, prevNew, matchedNew)
            }
            prevOld = matchedOld + 1
            prevNew = matchedNew + 1
        }
        if (prevOld < oldLines.size || prevNew < newLines.size) {
            hunks += lineHunkTrimmedToChars(oldText, newText, oldOffsets, newOffsets, prevOld, oldLines.size, prevNew, newLines.size)
        }
        return hunks
    }

    private fun lineHunkTrimmedToChars(
        oldText: String,
        newText: String,
        oldOffsets: IntArray,
        newOffsets: IntArray,
        oldLineStart: Int,
        oldLineEnd: Int,
        newLineStart: Int,
        newLineEnd: Int,
    ): Changed {
        val oldStart = oldOffsets[oldLineStart]
        val oldEnd = oldOffsets[oldLineEnd]
        val newStart = newOffsets[newLineStart]
        val newEnd = newOffsets[newLineEnd]
        val trimmed = compute(oldText.substring(oldStart, oldEnd), newText.substring(newStart, newEnd))
        return Changed(
            oldStart = oldStart + trimmed.oldStart,
            oldEnd = oldStart + trimmed.oldEnd,
            newStart = newStart + trimmed.newStart,
            newEnd = newStart + trimmed.newEnd,
        )
    }

    /** Splits [text] into lines, each retaining its trailing `'\n'` (if any) so offsets/spans reconstruct exactly. */
    private fun splitKeepingTerminators(text: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                lines += text.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < text.length) lines += text.substring(start)
        return lines
    }

    /** `offsets[i]` = character offset in the original text where `lines[i]` starts; `offsets[lines.size]` = total length. */
    private fun cumulativeOffsets(lines: List<String>): IntArray {
        val offsets = IntArray(lines.size + 1)
        for (i in lines.indices) offsets[i + 1] = offsets[i] + lines[i].length
        return offsets
    }

    /**
     * Classic LCS backtrace: the maximal sequence of (oldIndex, newIndex) pairs of *identical*
     * lines, in increasing order of both indices — i.e. which lines are common to both texts and
     * can be left untouched; the gaps between consecutive pairs are the changed hunks.
     */
    private fun lcsLineMatches(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val matches = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    matches += i to j
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return matches
    }

    private const val MAX_LINES_FOR_LINE_DIFF = 4000
}
