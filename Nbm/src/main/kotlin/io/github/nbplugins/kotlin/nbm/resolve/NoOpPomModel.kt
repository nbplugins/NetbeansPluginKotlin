// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.openapi.Disposable
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
 * - Returns a [TreeAspect] instance from [getModelAspect] when asked for `TreeAspect.class`
 *   (the only aspect actually consulted by [com.intellij.psi.impl.source.tree.ChangeUtil]).
 * - Ignores all listeners — we don't need IDE-side reactions to PSI changes.
 *
 * Registered as a project-level service in [KotlinAnalysisAPISession.installPomModel].
 */
class NoOpPomModel : UserDataHolderBase(), PomModel {
    private val treeAspect = TreeAspect()

    @Suppress("UNCHECKED_CAST")
    override fun <T : PomModelAspect> getModelAspect(aClass: Class<T>): T? =
        if (aClass == TreeAspect::class.java) treeAspect as T else null

    override fun addModelListener(listener: PomModelListener) { /* no-op */ }
    override fun addModelListener(listener: PomModelListener, parentDisposable: Disposable) { /* no-op */ }
    override fun removeModelListener(listener: PomModelListener) { /* no-op */ }

    override fun runTransaction(transaction: PomTransaction) {
        transaction.run()
    }
}
