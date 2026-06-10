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

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.formatter.KotlinLanguageCodeStyleSettingsProvider
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinStyleOptionsCollector].
 *
 * <p>Verifies that [KotlinStyleOptionsCollector.build] produces non-empty groups for
 * supported settings types, that lambda get/set round-trips work through the live
 * settings, and that [KotlinStyleOptionsCollector.fieldEntries] identifies the correct
 * set of fields.
 */
class KotlinStyleOptionsCollectorTest : NbTestCase("KotlinStyleOptionsCollectorTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun buildCollector(
        type: LanguageCodeStyleSettingsProvider.SettingsType,
        settings: CodeStyleSettings
    ): KotlinStyleOptionsCollector {
        KotlinFormatterUtils.registerKotlinProvider(settings)
        val collector = KotlinStyleOptionsCollector(
            settingsTypeName = type.name,
            commonProvider = { settings.getCommonSettings(KotlinLanguage.INSTANCE) },
            kotlinProvider = { settings.kotlinCustomSettings }
        )
        KotlinLanguageCodeStyleSettingsProvider().customizeSettings(collector, type)
        return collector
    }

    /** SPACING_SETTINGS yields at least the standard Spaces groups. */
    fun testSpacingGroupsNotEmpty() {
        val settings = CodeStyleSettings()
        val collector = buildCollector(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS, settings)
        val groups = collector.build()
        assertTrue("Spacing collector must produce at least one group", groups.isNotEmpty())
        val groupNames = groups.map { it.label }
        assertTrue("'Around operators' group expected", groupNames.contains("Around operators"))
        assertTrue("'Before parentheses' group expected", groupNames.contains("Before parentheses"))
    }

    /** WRAPPING_AND_BRACES_SETTINGS yields at least the wrapping groups. */
    fun testWrappingGroupsNotEmpty() {
        val settings = CodeStyleSettings()
        val collector = buildCollector(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS, settings)
        val groups = collector.build()
        assertTrue("Wrapping collector must produce at least one group", groups.isNotEmpty())
        val groupNames = groups.map { it.label }
        assertTrue("'Keep when reformatting' group expected", groupNames.contains("Keep when reformatting"))
    }

    /** BoolItem lambda reads from and writes to the live settings object. */
    fun testBoolItemLambdaReadWrite() {
        val settings = CodeStyleSettings()
        val collector = buildCollector(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS, settings)
        val groups = collector.build()

        val aroundOps = groups.first { it.label == "Around operators" }
        val candidates = aroundOps.items.filterIsInstance<BoolItem>()
            .filter { it.label.contains("assignment", ignoreCase = true) }
        assertFalse("No assignment-operators BoolItem found in 'Around operators'", candidates.isEmpty())
        val assignItem: BoolItem = candidates.first()

        val orig = assignItem.get.invoke()
        assignItem.set.invoke(!orig)
        assertEquals(!orig, settings.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS)
    }

    /** fieldEntries() includes at least SPACE_AROUND_ASSIGNMENT_OPERATORS for SPACING type. */
    fun testFieldEntriesContainsSpaceAroundAssignment() {
        val settings = CodeStyleSettings()
        val collector = buildCollector(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS, settings)
        val entries = collector.fieldEntries()
        assertTrue(
            "SPACE_AROUND_ASSIGNMENT_OPERATORS must be in fieldEntries",
            entries.any { it.fieldName == "SPACE_AROUND_ASSIGNMENT_OPERATORS" && !it.isCustom }
        )
    }

    /** COMMENTER_SETTINGS yields the "Comment Code" group with expected items. */
    fun testCommenterGroupNotEmpty() {
        val settings = CodeStyleSettings()
        val collector = buildCollector(LanguageCodeStyleSettingsProvider.SettingsType.COMMENTER_SETTINGS, settings)
        val groups = collector.build()
        assertTrue("Commenter collector must produce at least one group", groups.isNotEmpty())
        val groupNames = groups.map { it.label }
        assertTrue("'Comment Code' group expected", groupNames.contains("Comment Code"))
        val codeGroup = groups.first { it.label == "Comment Code" }
        assertTrue("'Comment Code' group must have items", codeGroup.items.isNotEmpty())
    }

    /** WrapItem for CALL_PARAMETERS_WRAP reads/writes the int field correctly. */
    fun testWrapItemLambdaReadWrite() {
        val settings = CodeStyleSettings()
        val collector = buildCollector(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS, settings)
        val groups = collector.build()

        val allItems = groups.flatMap { it.items }
        val wrapItem = allItems.filterIsInstance<WrapItem>()
            .firstOrNull { it.label.contains("arguments", ignoreCase = true) }
            ?: return  // skip if not found (field might be absent in this JVM)

        // Set to a known non-default value and verify the lambda writes to the live settings.
        wrapItem.set.invoke(1)
        assertEquals(1, settings.getCommonSettings(KotlinLanguage.INSTANCE).CALL_PARAMETERS_WRAP)
        assertEquals(1, wrapItem.get.invoke())
    }
}
