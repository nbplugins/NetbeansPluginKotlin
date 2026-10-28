// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.pom.PomModel
import com.intellij.pom.PomModelAspect
import com.intellij.pom.PomTransaction
import com.intellij.pom.event.PomModelListener
import com.intellij.pom.tree.TreeAspect

/**
 * Minimal [PomModel] stub for the standalone K2 environment.
 *
 * The real implementation ([com.intellij.pom.core.impl.PomModelImpl]) coordinates
 * IDE-side listeners, command processor, undo, code formatting, and PSI/document
 * synchronization. None of that exists in our embedded `MockProject`.
 *
 * This stub:
 * - Runs transactions synchronously by calling [PomTransaction.run]; the transaction's
 *   PSI tree mutation completes before this method returns.
 * - Drops the cached K2 analysis sessions after a transaction that edits frozen content
 *   ([KotlinAnalysisAPISession.invalidateProjectCaches]). The Java-to-Kotlin converter freezes the
 *   file it converts and then edits that PSI by hand, resolving the references it has just built.
 *   A non-physical session file fires no PSI change events, so in a real IDE `LLFirSessionInvalidationListener`
 *   would never drop the pre-edit snapshot: the resolution would return `null` and that failed
 *   result would stick on the reference. The drop is limited to frozen content on purpose — an
 *   ordinary refactoring runs inside the same kind of transaction, but there the documents are not
 *   committed until the transaction completes, and dropping the caches mid-way makes the
 *   refactoring observe a half-committed tree (`Document/PSI mismatch`).
 * - Returns a [TreeAspect] instance from [getModelAspect] when asked for `TreeAspect.class`
 *   (the only aspect actually consulted by [com.intellij.psi.impl.source.tree.ChangeUtil]).
 * - Ignores all listeners — we don't need IDE-side reactions to PSI changes.
 *
 * Registered as a project-level service in [KotlinAnalysisAPISession.installPomModel].
 *
 * @param project the project this model belongs to; its analysis caches are dropped after each
 *   transaction that edits frozen content
 */
class NoOpPomModel(private val project: Project) : UserDataHolderBase(), PomModel {
    private val treeAspect = TreeAspect()

    @Suppress("UNCHECKED_CAST")
    override fun <T : PomModelAspect> getModelAspect(aClass: Class<T>): T? =
        if (aClass == TreeAspect::class.java) treeAspect as T else null

    override fun addModelListener(listener: PomModelListener) { /* no-op */ }
    override fun addModelListener(listener: PomModelListener, parentDisposable: Disposable) { /* no-op */ }
    override fun removeModelListener(listener: PomModelListener) { /* no-op */ }

    /**
     * Runs [transaction] and, when it edited frozen content, drops the cached analysis sessions.
     *
     * The invalidation must happen only after the transaction has run: the caches it drops are
     * rebuilt from the PSI tree, which is mid-mutation until `run()` returns. It is skipped outside
     * frozen content because there the transaction belongs to an ordinary refactoring, whose
     * documents the platform commits after the transaction — see the class KDoc.
     *
     * @param transaction the PSI mutation to apply
     */
    override fun runTransaction(transaction: PomTransaction) {
        try {
            transaction.run()
        } finally {
            if (KotlinAnalysisAPISession.forProject(project)?.hasFrozenContent == true) {
                KotlinAnalysisAPISession.invalidateProjectCaches(project, "PSI transaction over frozen content")
            }
        }
    }
}
