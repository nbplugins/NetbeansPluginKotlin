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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import org.netbeans.junit.NbTestCase

/**
 * Unit tests for [KaIdeaReflection].
 *
 * The helper exists to reach `protected` members of the compiled IDEA inspection/intention
 * classes, so the tests exercise the same class-hierarchy walk against a local hierarchy:
 * a base declaration, a derived override, overloads distinguished by arity, and a
 * compiler-generated bridge method that must be skipped.
 */
class KaIdeaReflectionTest : NbTestCase("KaIdeaReflectionTest") {

    /** A member declared only in the base class is found by walking up the hierarchy. */
    fun testCallFindsBaseClassMember() {
        assertEquals("base", KaIdeaReflection.call(Derived(), "baseOnly", 0))
    }

    /** The most-derived override wins over the base declaration. */
    fun testCallPrefersMostDerivedOverride() {
        assertEquals("derived", KaIdeaReflection.call(Derived(), "overridden", 0))
    }

    /** Overloads are selected by parameter count. */
    fun testCallSelectsByArity() {
        assertEquals("one:7", KaIdeaReflection.call(Overloaded(), "describe", 1, 7))
        assertEquals("two:7:8", KaIdeaReflection.call(Overloaded(), "describe", 2, 7, 8))
    }

    /** Compiler-generated bridge methods are skipped, so the real override is invoked. */
    fun testCallSkipsBridgeMethod() {
        assertEquals("boxed", KaIdeaReflection.call(StringBox(), "unwrap", 0))
    }

    /** A missing member returns null instead of throwing. */
    fun testCallReturnsNullWhenAbsent() {
        assertNull(KaIdeaReflection.call(Derived(), "doesNotExist", 0))
    }

    /** A member whose parameter count does not match any overload returns null. */
    fun testCallReturnsNullOnArityMismatch() {
        assertNull(KaIdeaReflection.call(Overloaded(), "describe", 3, 1, 2, 3))
    }

    /** A `protected` member is reachable, which is the whole point of the helper. */
    fun testCallReachesProtectedMember() {
        assertEquals("protected", KaIdeaReflection.call(Derived(), "hidden", 0))
    }

    /** [KaIdeaReflection.callOrFail] returns the value of an existing member. */
    fun testCallOrFailReturnsValue() {
        assertEquals("derived", KaIdeaReflection.callOrFail(Derived(), "overridden", 0))
    }

    /** [KaIdeaReflection.callOrFail] returns null for a `void` method, which is a success. */
    fun testCallOrFailReturnsNullForVoidMethod() {
        assertNull(KaIdeaReflection.callOrFail(VoidMethod(), "run", 0))
    }

    /** [KaIdeaReflection.callOrFail] fails loudly when the member does not exist. */
    fun testCallOrFailThrowsWhenAbsent() {
        try {
            KaIdeaReflection.callOrFail(Derived(), "doesNotExist", 0)
            fail("Expected an IllegalStateException for a missing member")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "Message should name the missing member",
                expected.message!!.contains("doesNotExist")
            )
        }
    }

    /** Base class declaring the members the derived class partly overrides. */
    private open class Base {
        protected fun hidden(): String = "protected"

        fun baseOnly(): String = "base"

        open fun overridden(): String = "base"
    }

    /** Derived class overriding one member and inheriting the others. */
    private class Derived : Base() {
        override fun overridden(): String = "derived"
    }

    /** Class with same-named overloads of different arity. */
    private class Overloaded {
        fun describe(a: Int): String = "one:$a"

        fun describe(a: Int, b: Int): String = "two:$a:$b"
    }

    /** Class whose method returns `Unit`, compiled to a `void` method. */
    private class VoidMethod {
        fun run() {
        }
    }

    /** Generic interface whose implementation gets a compiler-generated bridge method. */
    private interface Box<T> {
        fun unwrap(): T
    }

    /** Implementation returning [String], producing a synthetic `unwrap(): Object` bridge. */
    private class StringBox : Box<String> {
        override fun unwrap(): String = "boxed"
    }
}
