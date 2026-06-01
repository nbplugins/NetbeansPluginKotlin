/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.options.formatter

import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import org.junit.Test
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinFormatterOtherPanel].
 */
class KotlinFormatterOtherPanelTest : NbTestCase("KotlinFormatterOtherPanelTest") {

    /** In-memory [Preferences] stub for testing without a real NetBeans runtime. */
    private class MapPreferences : AbstractPreferences(null, "") {
        private val map = mutableMapOf<String, String>()
        override fun putSpi(key: String, value: String) { map[key] = value }
        override fun getSpi(key: String): String? = map[key]
        override fun removeSpi(key: String) { map.remove(key) }
        override fun removeNodeSpi() = Unit
        override fun keysSpi(): Array<String> = map.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = emptyArray()
        override fun childSpi(name: String): AbstractPreferences = MapPreferences()
        override fun syncSpi() = Unit
        override fun flushSpi() = Unit
    }

    /** Default checkbox state is false (matches KotlinCodeStyleSettings defaults). */
    @Test
    fun testDefaultsAreFalse() {
        val prefs: Preferences = MapPreferences()
        val panel = KotlinFormatterOtherPanel {}
        panel.load(prefs)
        // checkboxes should be unchecked when prefs contains no values
        val trailingDecl = prefs.getBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA, false)
        val trailingCall = prefs.getBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, false)
        assertFalse(trailingDecl)
        assertFalse(trailingCall)
    }

    /** store() writes what was loaded back to prefs unchanged. */
    @Test
    fun testRoundTrip() {
        val prefs: Preferences = MapPreferences()
        prefs.putBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA, true)
        prefs.putBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, false)

        val panel = KotlinFormatterOtherPanel {}
        panel.load(prefs)

        // write to a fresh prefs node
        val out: Preferences = MapPreferences()
        panel.store(out)

        assertTrue(out.getBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA, false))
        assertFalse(out.getBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, true))
    }

    /** onChange callback is not invoked during load(). */
    @Test
    fun testOnChangeNotCalledOnLoad() {
        val prefs: Preferences = MapPreferences()
        var callCount = 0
        val panel = KotlinFormatterOtherPanel { callCount++ }
        panel.load(prefs)
        assertEquals(0, callCount)
    }
}
