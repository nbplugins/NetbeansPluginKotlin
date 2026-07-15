/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.diagnostics.parser

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.netbeans.modules.parsing.api.Source
import org.netbeans.modules.parsing.api.Task
import org.netbeans.modules.parsing.spi.SourceModificationEvent
import utils.KotlinTestCase

/**
 * Regression test: [KotlinParser.getResult] must not be corrupted when a *different*
 * [KotlinParser] instance parses another file in between this instance's [KotlinParser.parse]
 * and [KotlinParser.getResult] calls (e.g. during bulk background pre-analysis of many files).
 *
 * NetBeans creates one [KotlinParser] instance per file per parse cycle and calls `parse()`
 * once, then `getResult()` once per registered CSL task on that same instance — so per-instance
 * state (like the existing `snapshot` field) must be enough to serve `getResult()` correctly
 * regardless of what other [KotlinParser] instances do concurrently.
 */
class KotlinParserConcurrencyTest : KotlinTestCase("KotlinParserConcurrency", "diagnostics") {

    override fun tearDown() {
        KotlinAnalysisAPISession.disposeAll()
        super.tearDown()
    }

    fun testGetResult_notCorruptedByConcurrentParseOfAnotherFile() {
        val fo1 = dir.getFileObject("checkTypeMismatch.kt")
        val fo2 = dir.getFileObject("checkUnusedVariable.kt")
        assertNotNull("checkTypeMismatch.kt must exist in test resources", fo1)
        assertNotNull("checkUnusedVariable.kt must exist in test resources", fo2)

        val source1 = Source.create(fo1!!)
        val source2 = Source.create(fo2!!)
        val snapshot1 = source1.createSnapshot()
        val snapshot2 = source2.createSnapshot()

        val parser1 = KotlinParser()
        val parser2 = KotlinParser()
        val task = object : Task() {}

        parser1.parse(snapshot1, task, object : SourceModificationEvent(source1) {})
        // Simulate a second file being parsed (by a different KotlinParser instance) before
        // this instance's getResult() is called for its own file.
        parser2.parse(snapshot2, task, object : SourceModificationEvent(source2) {})

        val result1 = parser1.getResult(task) as? KotlinParserResult
        assertNotNull(
            "parser1.getResult() must return checkTypeMismatch.kt's result, " +
                "not be corrupted by the concurrently parsed checkUnusedVariable.kt",
            result1
        )
        assertEquals(fo1.path, result1!!.file.path)

        val result2 = parser2.getResult(task) as? KotlinParserResult
        assertNotNull("parser2.getResult() must return checkUnusedVariable.kt's result", result2)
        assertEquals(fo2.path, result2!!.file.path)
    }
}
