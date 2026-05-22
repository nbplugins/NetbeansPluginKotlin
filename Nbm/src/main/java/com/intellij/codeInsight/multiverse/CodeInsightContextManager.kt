package com.intellij.codeInsight.multiverse

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import kotlinx.coroutines.flow.Flow

/**
 * Stub interface overriding platform 253's CodeInsightContextManager.
 *
 * platform 242 had isSharedSourceSupportEnabled() on this interface; platform 253 removed it
 * from the interface declaration but kotlin-compiler-ir-for-ide:2.3.21 (compiled against 242)
 * still calls it via invokeinterface. Adding it here as a default method returning false
 * prevents NoSuchMethodError in standalone (non-multiverse) mode.
 */
interface CodeInsightContextManager {
    fun getCodeInsightContexts(file: VirtualFile): List<CodeInsightContext>
    fun getPreferredContext(file: VirtualFile): CodeInsightContext
    fun getCodeInsightContext(provider: FileViewProvider): CodeInsightContext
    fun getCodeInsightContextRaw(provider: FileViewProvider): CodeInsightContext
    fun getOrSetContext(provider: FileViewProvider, context: CodeInsightContext): CodeInsightContext
    fun getChangeFlow(): Flow<Unit>

    fun isSharedSourceSupportEnabled(): Boolean = false

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CodeInsightContextManager =
            project.getService(CodeInsightContextManager::class.java)
    }
}
