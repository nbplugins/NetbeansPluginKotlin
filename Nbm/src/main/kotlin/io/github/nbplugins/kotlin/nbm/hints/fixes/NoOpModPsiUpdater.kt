// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import com.intellij.codeInsight.template.Expression
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.ModShowConflicts
import com.intellij.modcommand.ModTemplateBuilder
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import java.util.function.Function

/**
 * No-op implementation of [ModPsiUpdater] for use in the NetBeans adapter layer.
 *
 * In IntelliJ, [ModPsiUpdater] manages writable copies of PSI elements and
 * deferred caret movement for the modcommand framework. In NetBeans standalone
 * mode, PSI mutations work directly on the in-memory tree, so most
 * [ModPsiUpdater] operations are no-ops. The only meaningful method is
 * [getWritable], which returns the element unchanged so that fix [invoke]
 * bodies that call `updater.getWritable(e)` receive the original element.
 */
object NoOpModPsiUpdater : ModPsiUpdater {

    /** Returns [e] unchanged; PSI mutations work directly in standalone mode. */
    override fun <E : PsiElement> getWritable(e: E): E = e

    /** Returns [file] unchanged; no copy is needed in standalone mode. */
    override fun getOriginalFile(file: PsiFile): PsiFile = file

    override fun highlight(element: PsiElement, key: TextAttributesKey) {}
    override fun highlight(range: TextRange, key: TextAttributesKey) {}
    override fun rename(owner: PsiNameIdentifierOwner, suggestions: List<String>) {}
    override fun rename(named: PsiNamedElement, context: PsiElement?, suggestions: List<String>) {}
    override fun trackDeclaration(element: PsiElement) {}
    override fun cancel(message: String) {}
    override fun showConflicts(conflicts: Map<PsiElement, ModShowConflicts.Conflict>) {}
    override fun message(message: String) {}

    /** Returns [NoOpTemplateBuilder]; template interaction is skipped in NetBeans. */
    override fun templateBuilder(): ModTemplateBuilder = NoOpTemplateBuilder

    // ModPsiNavigator — no-ops (no editor caret in NetBeans)
    override fun select(element: PsiElement) {}
    override fun select(range: TextRange) {}
    override fun moveCaretTo(offset: Int) {}
    override fun moveCaretTo(element: PsiElement) {}
    override fun getCaretOffset(): Int = 0

    /**
     * No-op implementation of [ModTemplateBuilder].
     * Template-based interactive field editing is not supported in NetBeans.
     */
    private object NoOpTemplateBuilder : ModTemplateBuilder {
        override fun field(e: PsiElement, expr: Expression): ModTemplateBuilder = this
        override fun field(e: PsiElement, name: String, expr: Expression): ModTemplateBuilder = this
        override fun field(e: PsiElement, range: TextRange, name: String, expr: Expression): ModTemplateBuilder = this
        override fun field(e: PsiElement, varName: String, expression: String, alwaysStopAt: Boolean): ModTemplateBuilder = this
        override fun finishAt(offset: Int): ModTemplateBuilder = this
        override fun onTemplateFinished(action: Function<in PsiFile, out com.intellij.modcommand.ModCommand>): ModTemplateBuilder = this
    }
}
