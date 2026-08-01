/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.refactoring

import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the machine-readable Kotlin refactoring coverage baseline.
 *
 * The visible matrix is maintained in `docs/refactoring-coverage.md`; its HTML comment records
 * link every status claim to a source adapter, IDEA engine, and test where applicable. Keeping
 * this check in the NBM test suite makes a moved source file or a removed test fail immediately
 * instead of silently making the parity document stale.
 */
class RefactoringCoverageBaselineTest : NbTestCase("RefactoringCoverageBaselineTest") {

    /** Verifies coverage records, paths, statuses, milestones, and declared row totals. */
    fun testCoverageRecords_referenceExistingPathsAndMatchDeclaredCounts() {
        val repository = findRepositoryRoot()
        val matrix = repository.resolve("docs/refactoring-coverage.md")
        assertTrue("Coverage matrix must exist: $matrix", Files.isRegularFile(matrix))

        val records = Files.readAllLines(matrix)
            .mapNotNull(::parseRecord)
        assertEquals("Every coverage ID must occur exactly once", records.size, records.map { it.id }.toSet().size)
        assertEquals("F0 baseline row count", 24, records.size)

        records.forEach { record ->
            assertTrue("Unknown status for ${record.id}: ${record.status}", record.status in STATUSES)
            assertTrue("Unknown milestone for ${record.id}: ${record.milestone}", record.milestone in MILESTONES)
            assertPathExists(repository, record.idea, "IDEA source", record.id)
            if (record.status == "absent") {
                assertEquals("Absent ${record.id} must not claim a NetBeans adapter", "none", record.netbeans)
                assertEquals("Absent ${record.id} must not claim a test", "none", record.test)
            } else {
                assertPathExists(repository, record.netbeans, "NetBeans adapter", record.id)
                assertPathExists(repository, record.test, "NetBeans test", record.id)
            }
        }

        val totals = "**Baseline counts:** 0 complete, 18 partial, 6 absent, 24 total."
        assertTrue("Visible baseline totals must match the records", Files.readString(matrix).contains(totals))
        assertEquals(18, records.count { it.status == "partial" })
        assertEquals(6, records.count { it.status == "absent" })
    }

    /** Finds the checkout root from Surefire's module working directory. */
    private fun findRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) && Files.isDirectory(candidate.resolve("Nbm"))) {
                return candidate
            }
            candidate = candidate.parent
        }
        fail("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        error("unreachable")
    }

    /** Parses one stable machine-readable coverage comment. */
    private fun parseRecord(line: String): CoverageRecord? {
        if (!line.startsWith("<!-- refactoring-coverage: ")) return null
        val values = line.removePrefix("<!-- refactoring-coverage: ").removeSuffix(" -->")
            .split("; ")
            .associate { item -> item.substringBefore('=') to item.substringAfter('=') }
        return CoverageRecord(
            id = values.getValue("id"),
            status = values.getValue("status"),
            idea = values.getValue("idea"),
            netbeans = values.getValue("netbeans"),
            test = values.getValue("test"),
            milestone = values.getValue("milestone"),
        )
    }

    /** Verifies one non-sentinel repository-relative path. */
    private fun assertPathExists(repository: Path, relativePath: String, label: String, id: String) {
        assertTrue("$label path for $id must exist: $relativePath", Files.exists(repository.resolve(relativePath)))
    }

    /** One machine-readable matrix row. */
    private data class CoverageRecord(
        val id: String,
        val status: String,
        val idea: String,
        val netbeans: String,
        val test: String,
        val milestone: String,
    )

    private companion object {
        private val STATUSES = setOf("complete", "partial", "absent")
        private val MILESTONES = (0..8).mapTo(mutableSetOf()) { "F$it" }
    }
}
