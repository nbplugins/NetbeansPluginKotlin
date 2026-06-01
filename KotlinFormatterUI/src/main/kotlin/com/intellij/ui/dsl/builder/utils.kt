// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
//
// Stub replacement for platform-api utils.kt.  Provides DslComponentProperty and
// associated constants without the BrowserUtil / UINumericRange / IconLoader imports
// that are absent from the NetBeans build classpath.
package com.intellij.ui.dsl.builder

/**
 * Component client-property keys used by the Kotlin UI DSL to annotate Swing
 * components with layout and rendering hints.
 */
enum class DslComponentProperty {
    /** Marks the component as the label for its row. */
    ROW_LABEL,

    /**
     * Custom visual paddings used instead of {@code JComponent.getInsets}.
     * Value: {@code UnscaledGaps} or {@code Gaps}.
     */
    VISUAL_PADDINGS,

    /**
     * Overrides the default vertical component gap behaviour.
     * Value: {@code VerticalComponentGap}.
     */
    VERTICAL_COMPONENT_GAP,

    /**
     * Delegates {@code JLabel.setLabelFor} to a child component.
     * Value: {@code JComponent}.
     */
    LABEL_FOR,

    /**
     * Points to the main interactive component inside a compound widget.
     * Value: {@code JComponent}.
     */
    INTERACTIVE_COMPONENT,

    /**
     * Provides custom icon resolution for {@code <icon src='key'>} tags.
     * Value: {@code IconsProvider}.
     */
    ICONS_PROVIDER,
}

/** Default comment width in columns. */
const val DEFAULT_COMMENT_WIDTH: Int = 70

/** Text uses word wrap when there is insufficient width. */
const val MAX_LINE_LENGTH_WORD_WRAP: Int = -1

/** Text is not wrapped and uses only HTML {@code <br>} markup. */
const val MAX_LINE_LENGTH_NO_WRAP: Int = Int.MAX_VALUE

/**
 * Nullable vertical-gap override for cells that need non-default spacing.
 *
 * @property top    {@code true} to force a gap above, {@code false} to force none,
 *                  {@code null} to use the default
 * @property bottom {@code true} to force a gap below, {@code false} to force none,
 *                  {@code null} to use the default
 */
data class VerticalComponentGap(val top: Boolean? = null, val bottom: Boolean? = null)

/**
 * Removes wrapping {@code <html>…</html>} tags from a resource string.
 *
 * @param s the string to clean
 * @return the inner content, or {@code s} unchanged if it has no wrapper tags
 */
fun cleanupHtml(s: String): String {
    val regex = Regex("\\s*<html>(?<body>.*)</html>\\s*", RegexOption.IGNORE_CASE)
    return regex.matchEntire(s)?.groups?.get("body")?.value ?: s
}
