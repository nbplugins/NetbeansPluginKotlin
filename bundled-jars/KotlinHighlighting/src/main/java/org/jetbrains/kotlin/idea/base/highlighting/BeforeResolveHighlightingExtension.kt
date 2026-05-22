// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.highlighting

import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import org.jetbrains.kotlin.idea.highlighter.visitor.AbstractHighlightingVisitor

/**
 * Minimal stub for the platform [BeforeResolveHighlightingExtension] interface.
 *
 * The canonical definition lives in [org.jetbrains.kotlin.idea.base.highlighting.KotlinBeforeResolveHighlightingPass],
 * which cannot be compiled here because it depends on [org.jetbrains.kotlin.idea.KotlinFileType] —
 * an IDEA-plugin-internal class absent from published Maven artifacts. This stub satisfies the
 * reference from [org.jetbrains.kotlin.idea.base.highlighting.beforeResolve.DeclarationHighlightingVisitor]
 * and [org.jetbrains.kotlin.idea.base.highlighting.beforeResolve.AnnotationEntryHighlightingVisitor].
 */
interface BeforeResolveHighlightingExtension {
    fun createVisitor(holder: HighlightInfoHolder): AbstractHighlightingVisitor
}
