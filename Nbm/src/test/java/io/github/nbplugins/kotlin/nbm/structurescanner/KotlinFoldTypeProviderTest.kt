/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.structurescanner

import org.netbeans.api.editor.fold.FoldType
import org.netbeans.junit.NbTestCase

/**
 * Unit tests for [KotlinFoldTypeProvider].
 *
 * Pure SPI behaviour, so the test extends [NbTestCase] directly (no NetBeans project
 * needed). It pins the public contract relied upon by the CSL fold pipeline: the set of
 * fold types, their codes (which must match the keys produced by `KotlinFoldingVisitor`
 * and consumed by `GsfFoldManager.addTree`), and the `getValues`/`inheritable` results.
 */
class KotlinFoldTypeProviderTest : NbTestCase("KotlinFoldTypeProviderTest") {

    private val provider = KotlinFoldTypeProvider()

    /** `getValues(FoldType.class)` returns exactly the three Kotlin fold types. */
    fun testGetValuesReturnsTheKotlinFoldTypes() {
        val values = provider.getValues(FoldType::class.java)
        assertNotNull("getValues(FoldType) must not be null", values)
        assertEquals(3, values!!.size)
        assertTrue(values.contains(KotlinFoldTypeProvider.IMPORTS))
        assertTrue(values.contains(KotlinFoldTypeProvider.COMMENTS))
        assertTrue(values.contains(KotlinFoldTypeProvider.CODE_BLOCKS))
    }

    /** `getValues` returns `null` for any kind other than [FoldType]. */
    fun testGetValuesReturnsNullForOtherTypes() {
        assertNull(provider.getValues(Any::class.java))
        assertNull(provider.getValues(String::class.java))
    }

    /** The provider is not inheritable (Kotlin has no embedded child languages). */
    fun testInheritableIsFalse() {
        assertFalse(provider.inheritable())
    }

    /**
     * The fold-type codes must stay exactly `imports`/`comments`/`codeblocks` — they are
     * the contract with `KotlinFoldingVisitor`'s map keys and `GsfFoldManager`'s
     * registered-type matching; changing them silently breaks folding.
     */
    fun testFoldTypeCodesMatchVisitorKeys() {
        assertEquals("imports", KotlinFoldTypeProvider.IMPORTS.code())
        assertEquals("comments", KotlinFoldTypeProvider.COMMENTS.code())
        assertEquals("codeblocks", KotlinFoldTypeProvider.CODE_BLOCKS.code())
    }
}
