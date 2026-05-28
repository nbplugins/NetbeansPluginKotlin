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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.completion

import org.netbeans.modules.csl.api.ElementKind
import utils.KotlinTestCase

/**
 * Unit tests for [KtElementHandle].
 *
 * Verifies that all [org.netbeans.modules.csl.api.ElementHandle] methods return the
 * expected values and that [KtElementHandle.signatureEquals] correctly identifies handles
 * that point to the same source location.
 */
class KtElementHandleTest : KotlinTestCase("KtElementHandle test", "completion") {

    private fun makeHandle(offset: Int = 10, name: String = "myFun", kind: ElementKind = ElementKind.METHOD) =
        KtElementHandle(dir.getFileObject("checkAfterDotFiltering.kt")!!, offset, name, kind)

    /** [KtElementHandle.getName] returns the name passed to the constructor. */
    fun testGetName() {
        assertEquals("myFun", makeHandle(name = "myFun").name)
    }

    /** [KtElementHandle.getKind] returns the kind passed to the constructor. */
    fun testGetKind() {
        assertEquals(ElementKind.METHOD, makeHandle(kind = ElementKind.METHOD).kind)
    }

    /** [KtElementHandle.getMimeType] always returns the Kotlin MIME type. */
    fun testGetMimeType() {
        assertEquals("text/x-kotlin", makeHandle().mimeType)
    }

    /** [KtElementHandle.getIn] returns an empty string. */
    fun testGetIn() {
        assertEquals("", makeHandle().`in`)
    }

    /** [KtElementHandle.getModifiers] returns an empty set. */
    fun testGetModifiers() {
        assertTrue(makeHandle().modifiers.isEmpty())
    }

    /** [KtElementHandle.getOffsetRange] spans `[offset, offset + name.length)`. */
    fun testGetOffsetRange() {
        val handle = makeHandle(offset = 20, name = "myFun")
        val range = handle.getOffsetRange(null)
        assertEquals(20, range.start)
        assertEquals(25, range.end)   // "myFun".length == 5
    }

    /**
     * [KtElementHandle.signatureEquals] returns `true` when both handles point to the
     * same offset in the same file.
     */
    fun testSignatureEquals_sameOffsetSameFile() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val h1 = KtElementHandle(fo, 42, "fun1", ElementKind.METHOD)
        val h2 = KtElementHandle(fo, 42, "fun1", ElementKind.METHOD)
        assertTrue(h1.signatureEquals(h2))
    }

    /** [KtElementHandle.signatureEquals] returns `false` when offsets differ. */
    fun testSignatureEquals_differentOffset() {
        val fo = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val h1 = KtElementHandle(fo, 10, "fun1", ElementKind.METHOD)
        val h2 = KtElementHandle(fo, 20, "fun1", ElementKind.METHOD)
        assertFalse(h1.signatureEquals(h2))
    }

    /** [KtElementHandle.signatureEquals] returns `false` when files differ. */
    fun testSignatureEquals_differentFile() {
        val fo1 = dir.getFileObject("checkAfterDotFiltering.kt")!!
        val fo2 = dir.getFileObject("checkBasicAny.kt")!!
        val h1 = KtElementHandle(fo1, 42, "fun1", ElementKind.METHOD)
        val h2 = KtElementHandle(fo2, 42, "fun1", ElementKind.METHOD)
        assertFalse(h1.signatureEquals(h2))
    }

    /** [KtElementHandle.signatureEquals] returns `false` for a non-[KtElementHandle] argument. */
    fun testSignatureEquals_differentType() {
        val handle = makeHandle()
        assertFalse(handle.signatureEquals(org.netbeans.modules.csl.api.ElementHandle.UrlHandle("http://example.com")))
    }
}
