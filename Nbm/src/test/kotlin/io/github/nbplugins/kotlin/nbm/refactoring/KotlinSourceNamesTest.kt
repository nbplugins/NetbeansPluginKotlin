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

import io.github.nbplugins.kotlin.refactoring.KotlinSourceNames
import org.jetbrains.kotlin.name.FqName
import org.netbeans.junit.NbTestCase

/** Unit tests for the Kotlin source-text name renderer. */
class KotlinSourceNamesTest : NbTestCase("KotlinSourceNamesTest") {

    /** Verifies plain identifiers need no escaping. */
    fun testIsPlainIdentifier_acceptsPlainNames() {
        assertTrue(KotlinSourceNames.isPlainIdentifier("moved"))
        assertTrue(KotlinSourceNames.isPlainIdentifier("_private"))
        assertTrue(KotlinSourceNames.isPlainIdentifier("Camel2Case"))
        assertTrue("soft keywords are legal identifiers", KotlinSourceNames.isPlainIdentifier("value"))
    }

    /** Verifies hard keywords cannot be written as bare identifiers. */
    fun testIsPlainIdentifier_rejectsHardKeywords() {
        assertFalse(KotlinSourceNames.isPlainIdentifier("class"))
        assertFalse(KotlinSourceNames.isPlainIdentifier("when"))
        assertFalse(KotlinSourceNames.isPlainIdentifier("object"))
    }

    /** Verifies names with spaces or a leading digit need backticks. */
    fun testIsPlainIdentifier_rejectsNonIdentifierSpellings() {
        assertFalse(KotlinSourceNames.isPlainIdentifier("two words"))
        assertFalse(KotlinSourceNames.isPlainIdentifier("2fast"))
        assertFalse(KotlinSourceNames.isPlainIdentifier(""))
    }

    /** Verifies a plain name is rendered unchanged. */
    fun testRenderIdentifier_keepsPlainName() {
        assertEquals("moved", KotlinSourceNames.renderIdentifier("moved"))
    }

    /** Verifies keywords and names with spaces are backticked. */
    fun testRenderIdentifier_backticksInvalidNames() {
        assertEquals("`when`", KotlinSourceNames.renderIdentifier("when"))
        assertEquals("`two words`", KotlinSourceNames.renderIdentifier("two words"))
    }

    /** Verifies a plain FQN is rendered unchanged. */
    fun testRenderFqName_keepsPlainSegments() {
        assertEquals("dest.moved", KotlinSourceNames.renderFqName(FqName("dest.moved")))
    }

    /** Verifies only the segments that need escaping are backticked. */
    fun testRenderFqName_escapesKeywordSegments() {
        assertEquals("a.`when`.C", KotlinSourceNames.renderFqName(FqName("a.when.C")))
    }
}
