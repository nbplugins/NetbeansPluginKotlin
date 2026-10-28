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
 * - Drops the cached K2 analysis sessions after every transaction ([KotlinAnalysisAPISession.invalidateProjectCaches]).
 *   In a real IDE a PSI change fires `PsiTreeChangeEvent`s on the project message bus, and
 *   `LLFirSessionInvalidationListener` reacts by clearing the same caches. A non-physical session
 *   file fires no such events, so without this call the analysis keeps serving the pre-edit
 *   snapshot: a reference the caller has just created resolves to `null`, and the failed result
 *   sticks on the reference forever. The Java-to-Kotlin converter depends on this: its
 *   post-processing edits accessors and then resolves the references it has just built.
 * - Returns a [TreeAspect] instance from [getModelAspect] when asked for `TreeAspect.class`
 *   (the only aspect actually consulted by [com.intellij.psi.impl.source.tree.ChangeUtil]).
 * - Ignores all listeners — we don't need IDE-side reactions to PSI changes.
 *
 * Registered as a project-level service in [KotlinAnalysisAPISession.installPomModel].
 *
 * @param project the project this model belongs to; its analysis caches are dropped after each
 *   committed transaction
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
     * Runs [transaction] and drops the cached analysis sessions afterwards.
     *
     * The invalidation must happen only after the transaction has run: the caches it drops are
     * rebuilt from the PSI tree, which is mid-mutation until `run()` returns.
     *
     * @param transaction the PSI mutation to apply
     */
    override fun runTransaction(transaction: PomTransaction) {
        try {
            transaction.run()
        } finally {
            KotlinAnalysisAPISession.invalidateProjectCaches(project, "PSI transaction")
        }
    }
}
