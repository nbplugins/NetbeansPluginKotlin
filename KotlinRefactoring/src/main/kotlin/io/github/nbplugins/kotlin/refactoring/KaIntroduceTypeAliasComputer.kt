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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggester
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Headless analysis engine for the **Introduce Type Alias** refactoring.
 *
 * Ported from IDEA's `IntroduceTypeAliasHandler` (K2 variant, ~543 LOC).  For the NetBeans MVP,
 * this engine handles the common case of introducing a simple (non-parameterised) type alias:
 * it finds the [KtTypeReference] under the caret, collects all textually identical type
 * references in the file as occurrence candidates, suggests an alias name via
 * [KotlinNameSuggester.suggestTypeAliasNameByPsi], and computes the insertion point (before the
 * enclosing top-level declaration or after the last import).
 *
 * Type-parameter extraction (for generic types like `List<String>` → `typealias Strings<T> = List<T>`)
 * is not supported in this version; the whole type text is used as the alias body.
 *
 * @param ktFile       the file being refactored
 * @param caretOffset  caret position within the file
 */
class KaIntroduceTypeAliasComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not on a type reference. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaIntroduceTypeAliasResult) : Outcome()
    }

    /**
     * Runs the analysis and returns an [Outcome].
     *
     * Returns [Outcome.NotApplicable] when the caret is not positioned on a type reference
     * (e.g. on a function keyword, expression, or whitespace).
     */
    fun compute(): Outcome = try {
        computeInternal()
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    private fun computeInternal(): Outcome {
        val leaf = ktFile.findElementAt(caretOffset) ?: return Outcome.NotApplicable

        // Walk up to the nearest KtTypeReference covering the caret.
        val typeRef = PsiTreeUtil.getParentOfType(leaf, KtTypeReference::class.java, false)
            ?: return Outcome.NotApplicable

        // Get the type element (the core syntactic type, without nullability wrapping).
        val typeElement = typeRef.typeElement ?: return Outcome.NotApplicable

        val typeText = typeRef.text.trim()
        if (typeText.isEmpty()) return Outcome.NotApplicable

        // Suggest alias name from the PSI structure (no K2 required).
        val suggestedName = KotlinNameSuggester.suggestTypeAliasNameByPsi(typeElement) { true }
            .ifBlank { deriveSimpleName(typeText) }

        // Collect all type references in the file with the same text as occurrence candidates.
        val occurrenceRanges = collectOccurrences(typeText, typeRef)

        // Find the insertion point: before the enclosing top-level declaration (or after imports).
        val insertOffset = computeInsertOffset(typeRef)

        // Compute available visibilities based on target scope.
        val visibilities = computeVisibilities(insertOffset)

        return Outcome.Ready(
            KaIntroduceTypeAliasResult(
                typeRefRange = typeRef.textRange,
                typeText = typeText,
                suggestedName = suggestedName,
                occurrenceRanges = occurrenceRanges,
                insertOffset = insertOffset,
                availableVisibilities = visibilities,
            )
        )
    }

    /**
     * Collects text ranges of all [KtTypeReference]s in the file that have the same trimmed text
     * as [typeText], excluding the originating [originRef] (which is included via [typeRefRange]).
     *
     * The originating reference is always included first in the returned list.
     */
    private fun collectOccurrences(typeText: String, originRef: KtTypeReference): List<TextRange> {
        val all = mutableListOf<TextRange>()
        // Include the origin reference as the first occurrence.
        all.add(originRef.textRange)
        ktFile.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitTypeReference(typeReference: KtTypeReference) {
                if (typeReference !== originRef && typeReference.text.trim() == typeText) {
                    all.add(typeReference.textRange)
                }
                super.visitTypeReference(typeReference)
            }
        })
        return all
    }

    /**
     * Computes the insertion offset for the `typealias` declaration.
     *
     * Walks up from [typeRef] to find the direct child of [KtFile] (a top-level declaration or
     * import list); inserts before that declaration.  Falls back to the end of the import list
     * (or position 0) when no enclosing top-level declaration is found.
     */
    private fun computeInsertOffset(typeRef: KtTypeReference): Int {
        val topLevelDecl = typeRef.parentsWithSelf
            .firstOrNull { it.parent is KtFile }
        return topLevelDecl?.startOffset
            ?: (ktFile.importList?.textRange?.endOffset?.plus(1) ?: 0)
    }

    /**
     * Returns applicable visibility modifiers for a `typealias` inserted at [insertOffset].
     *
     * At file level: `public`, `internal`, `private`.
     */
    private fun computeVisibilities(insertOffset: Int): List<String> =
        listOf("public", "internal", "private")

    /**
     * Derives a simple PascalCase alias name from [typeText] by stripping generic arguments and
     * capitalising the base name.  Used as a fallback when [KotlinNameSuggester.suggestTypeAliasNameByPsi]
     * returns a blank string.
     */
    private fun deriveSimpleName(typeText: String): String {
        val base = typeText.substringBefore('<').trim()
        return base.replaceFirstChar { it.uppercaseChar() }.ifBlank { "MyAlias" }
    }
}

/**
 * All data needed by the NetBeans apply element to perform the introduce-type-alias transformation.
 *
 * @param typeRefRange         range of the original type reference in the file (to be replaced by alias name)
 * @param typeText             text of the type reference (becomes the alias body)
 * @param suggestedName        suggested alias name derived from the type structure
 * @param occurrenceRanges     ranges of all identical type references in the file (including the original);
 *                             the first element is always the trigger reference
 * @param insertOffset         offset before which `typealias NAME = TYPE` is inserted
 * @param availableVisibilities applicable visibility keywords (`"public"`, `"internal"`, `"private"`)
 */
data class KaIntroduceTypeAliasResult(
    val typeRefRange: TextRange,
    val typeText: String,
    val suggestedName: String,
    val occurrenceRanges: List<TextRange>,
    val insertOffset: Int,
    val availableVisibilities: List<String>,
)
