// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.introduce

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.nextSiblingOfSameType
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Minimal stub for [ExtractableSubstringInfo]: represents a selected substring within a Kotlin
 * string-template expression. Only fields needed by [K2SemanticMatcher][org.jetbrains.kotlin.idea.k2.refactoring.introduce.K2SemanticMatcher]
 * and [K2ExtractableSubstringInfo][org.jetbrains.kotlin.idea.k2.refactoring.introduce.K2ExtractableSubstringInfo] are provided.
 */
abstract class ExtractableSubstringInfo(
    val startEntry: KtStringTemplateEntry,
    val endEntry: KtStringTemplateEntry,
    val prefix: String,
    val suffix: String,
) {
    abstract val isString: Boolean

    val template: KtStringTemplateExpression = startEntry.parent as KtStringTemplateExpression

    val entries: Sequence<KtStringTemplateEntry>
        get() {
            val templateEntries = template.entries
            val startIndex = templateEntries.indexOf(startEntry)
            val endIndex = templateEntries.indexOf(endEntry)
            return templateEntries.slice(startIndex..endIndex).asSequence()
        }

    val content: String get() = with(entries.map { it.text }.joinToString("")) {
        substring(prefix.length, length - suffix.length)
    }

    val contentRange: TextRange
        get() = TextRange(startEntry.startOffset + prefix.length, endEntry.endOffset - suffix.length)

    val relativeContentRange: TextRange
        get() = contentRange.shiftRight(-template.startOffset)

    /** Creates a PSI expression representing the extracted substring. */
    fun createExpression(): KtExpression {
        val text = if (prefix.isEmpty() && suffix.isEmpty() && startEntry == endEntry) {
            content
        } else {
            buildString {
                append('"')
                append(prefix)
                entries.forEach { append(it.text) }
                append(suffix)
                append('"')
            }
        }
        return KtPsiFactory(startEntry.project).createExpression(text)
    }

    abstract fun copy(
        newStartEntry: KtStringTemplateEntry,
        newEndEntry: KtStringTemplateEntry,
    ): ExtractableSubstringInfo
}

/** User-data property to attach an [ExtractableSubstringInfo] to a [KtExpression]. */
var KtExpression.extractableSubstringInfo: ExtractableSubstringInfo?
    by UserDataProperty(Key.create("EXTRACTED_SUBSTRING_INFO"))
