/*******************************************************************************
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.file.templates

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.structurescanner.KaStructureScanner
import utils.KotlinTestCase
import java.nio.charset.StandardCharsets

/**
 * Reproduces the bug where files produced by the New Kotlin Class wizard
 * appear correctly in the editor but the Navigator (KaStructureScanner)
 * shows nothing.
 *
 * Root cause: the K2 [KotlinAnalysisAPISession] is built once per project and
 * freezes its source-file list at construction time. Any file added later is
 * invisible to `getKtFileForPath`, so KaStructureScanner returns an empty
 * list. Calling [KotlinAnalysisAPISession.invalidate] after file creation
 * forces a rebuild on the next access and resolves the issue.
 *
 * The tests demonstrate this:
 *   - Reference file (already on disk at session-build time) → has structure.
 *   - File created at test time without invalidate → empty (regression guard).
 *   - File created at test time with invalidate → has structure (fix guard).
 */
class WizardFileCreationTest : KotlinTestCase("Wizard file creation", "wizardCreate") {

    private val BODY = "package wizardCreate\n\nclass %s {\n}\n"

    private fun structureItemCount(fileName: String): Int {
        // Force session init (without it KaStructureScanner returns null).
        KotlinAnalysisAPISession.getSession(project)
        val file = dir.getFileObject(fileName) ?: error("File not found in VFS: $fileName")
        val items = KaStructureScanner.getStructureItems(file, project) ?: return 0
        return items.size
    }

    /** Sanity check: the reference file (already on disk) produces a non-empty structure. */
    fun testReferenceFileHasStructure() {
        val n = structureItemCount("Reference.kt")
        assertTrue("Reference.kt should yield ≥1 structure item, got $n", n >= 1)
    }

    /**
     * Regression guard: a file created **without** invalidating the K2 session
     * stays invisible to KaStructureScanner. If this ever starts returning
     * items, the K2 session has gained auto-discovery — and the explicit
     * invalidate in the wizard becomes redundant.
     */
    fun testRegression_WithoutInvalidate_ProducesEmpty() {
        val name = "NoInvalidate"
        val createdFile = dir.createData(name, "kt")
        val lock = createdFile.lock()
        try {
            createdFile.getOutputStream(lock).use { out ->
                out.write(BODY.format(name).toByteArray(StandardCharsets.UTF_8))
            }
        } finally {
            lock.releaseLock()
        }

        val n = structureItemCount("$name.kt")
        assertEquals(
            "NoInvalidate.kt: K2 session built before file creation does not see it",
            0, n,
        )
    }

    /**
     * Fix guard: file created via the same VFS path plus an explicit
     * `KotlinAnalysisAPISession.invalidate(project)` call produces a non-empty
     * structure — confirming the wizard's [KtDefaultWizardIterator.instantiate]
     * fix.
     */
    fun testFix_WithInvalidate_ProducesStructure() {
        val name = "WithInvalidate"
        val createdFile = dir.createData(name, "kt")
        val lock = createdFile.lock()
        try {
            createdFile.getOutputStream(lock).use { out ->
                out.write(BODY.format(name).toByteArray(StandardCharsets.UTF_8))
            }
        } finally {
            lock.releaseLock()
        }
        KotlinAnalysisAPISession.invalidate(project)

        val n = structureItemCount("$name.kt")
        assertTrue("WithInvalidate.kt should yield ≥1 structure item after invalidate, got $n", n >= 1)
    }
}
