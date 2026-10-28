// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.codeInsight

import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Standalone form of IDEA's `KotlinOptimizeImportsFacility` service.
 *
 * The interface keeps the real shape, because the J2K post-processing
 * (`OptimizeImportsProcessing`) calls all three members. The standalone implementation deliberately
 * reports "imports already optimal" ([analyzeImports] returns `null`): unused-import detection
 * relies on the Kotlin resolution index, which the standalone `StandaloneAnalysisAPISession` does
 * not build.
 *
 * [replaceImports] is fully functional, so import rewriting works as soon as an implementation
 * supplies the optimized import list (and it can be exercised directly by tests).
 */
interface KotlinOptimizeImportsFacility {

    /**
     * The result of analysing the imports of a file.
     */
    interface ImportData {
        /** The imports that are not used by the file. */
        val unusedImports: List<KtImportDirective>
    }

    /**
     * Analyzes the imports of [file].
     *
     * @param file the file whose imports should be analyzed.
     * @return the analysis result, or `null` when the imports are already optimal.
     */
    fun analyzeImports(file: KtFile): ImportData?

    /**
     * Computes the import list [file] should have after optimization.
     *
     * @param file the file being optimized.
     * @param data the result of [analyzeImports].
     * @return the optimized imports, or `null` when they are already optimal.
     */
    fun prepareOptimizedImports(file: KtFile, data: ImportData): List<ImportPath>?

    /**
     * Replaces the imports of [file] with [imports].
     *
     * New imports are inserted before the old ones are deleted, so the import list keeps its
     * folding state.
     *
     * @param file the file whose imports should be replaced.
     * @param imports the complete new import list.
     */
    fun replaceImports(file: KtFile, imports: Iterable<ImportPath>) {
        val manager = PsiDocumentManager.getInstance(file.project)
        manager.getDocument(file)?.let { manager.commitDocument(it) }
        val importList = file.importList ?: return
        val oldImports = importList.imports
        val psiFactory = KtPsiFactory(file.project)
        for (importPath in imports) {
            val newImport = importList.addBefore(
                psiFactory.createImportDirective(importPath),
                oldImports.lastOrNull()
            ) // insert into the middle to keep collapsed state

            importList.addAfter(psiFactory.createWhiteSpace("\n"), newImport)
        }

        // remove old imports after adding new ones to keep imports folding state
        for (import in oldImports) {
            import.delete()
        }
    }

    companion object {
        private val INSTANCE = object : KotlinOptimizeImportsFacility {
            override fun analyzeImports(file: KtFile): ImportData? = null

            override fun prepareOptimizedImports(file: KtFile, data: ImportData): List<ImportPath>? = null
        }

        /**
         * Returns the facility instance used by the plugin.
         *
         * @return the standalone singleton.
         */
        @JvmStatic
        fun getInstance(): KotlinOptimizeImportsFacility = INSTANCE
    }
}
