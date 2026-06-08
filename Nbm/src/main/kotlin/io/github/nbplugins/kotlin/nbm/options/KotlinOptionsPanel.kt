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
package io.github.nbplugins.kotlin.nbm.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.options.formatter.CustomSchemeEntry
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterBlankLinesPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterImportsPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterIndentPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterCodeGenPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterOtherPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterTabWithPreview
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormattingPreviewPane
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinSchemeManager
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinSettingsTreePanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinStyleBar
import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane

/**
 * Root panel for Tools → Options → Kotlin.
 *
 * <p>Layout: a [KotlinStyleBar] (scheme selector and gear menu) at the top, with
 * a [JTabbedPane] below. Tabs that have a meaningful preview ([KotlinFormatterTabWithPreview])
 * embed a live [KotlinFormattingPreviewPane] on the right side of an inner split pane.
 * Tabs without a preview (Imports, Code Generation) are shown full-width.
 *
 * <p>The [onChange] callback is forwarded to [KotlinOptionsPanelController] so
 * that the Options dialog can track whether unsaved changes exist.
 *
 * @param onChange called whenever any control in any sub-panel changes value
 */
class KotlinOptionsPanel(private val onChange: () -> Unit) : JPanel(BorderLayout()) {

    private val indentPanel = KotlinFormatterIndentPanel(::onSettingChanged)
    private val blankLinesPanel = KotlinFormatterBlankLinesPanel(::onSettingChanged)
    private val otherPanel = KotlinFormatterOtherPanel(::onSettingChanged)
    private val spacesPanel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS, ::onSettingChanged)
    private val wrappingPanel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS, ::onSettingChanged)
    private val importsPanel = KotlinFormatterImportsPanel(::onSettingChanged)
    private val codeGenPanel = KotlinFormatterCodeGenPanel(::onSettingChanged)

    /**
     * Name of the currently active custom scheme, or {@code null} if a built-in
     * scheme is active. Updated on load and whenever the scheme changes via the
     * gear menu.
     */
    private var currentCustomSchemeName: String? = null

    private val styleBar = KotlinStyleBar(
        onStyleApplied    = ::onStyleApplied,
        currentSettingsProvider = ::collectCurrentSettings,
        onSchemeListChanged = { newName ->
            currentCustomSchemeName = newName
            onChange()
        }
    )

    private val tabIndent   = KotlinFormatterTabWithPreview(indentPanel,     PREVIEW_GENERAL,      ::collectSettingsInto)
    private val tabSpaces   = KotlinFormatterTabWithPreview(spacesPanel,     PREVIEW_GENERAL,      ::collectSettingsInto)
    private val tabWrapping = KotlinFormatterTabWithPreview(wrappingPanel,   PREVIEW_WRAPPING,     ::collectSettingsInto)
    private val tabBlank    = KotlinFormatterTabWithPreview(blankLinesPanel, PREVIEW_BLANK_LINES,  ::collectSettingsInto)
    private val tabOther    = KotlinFormatterTabWithPreview(otherPanel,      PREVIEW_GENERAL,      ::collectSettingsInto)

    private val previewPanes: List<KotlinFormattingPreviewPane> = listOf(
        tabIndent.previewPane, tabSpaces.previewPane, tabWrapping.previewPane,
        tabBlank.previewPane, tabOther.previewPane
    )

    private val tabs = JTabbedPane()

    init {
        tabs.addTab("Tabs & Indent",     tabIndent)
        tabs.addTab("Spaces",            tabSpaces)
        tabs.addTab("Wrapping & Braces", tabWrapping)
        tabs.addTab("Blank Lines",       tabBlank)
        tabs.addTab("Imports",           JScrollPane(importsPanel))
        tabs.addTab("Other",             tabOther)
        tabs.addTab("Code Generation",   codeGenPanel)
        add(styleBar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    /**
     * Populates all sub-panels from [prefs] and syncs the style bar.
     *
     * <p>If a custom scheme name is stored in [prefs] under [PREFS_KEY_CUSTOM_SCHEME],
     * the combo is set to that custom scheme entry. Otherwise the built-in entry
     * matching the stored [KotlinCodeStyleSettings.CODE_STYLE_DEFAULTS] is shown.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        currentCustomSchemeName = prefs.get(PREFS_KEY_CUSTOM_SCHEME, null)
        styleBar.setCurrentScheme(tmp.kotlinCustomSettings.CODE_STYLE_DEFAULTS, currentCustomSchemeName)
        indentPanel.load(prefs)
        blankLinesPanel.load(prefs)
        spacesPanel.load(prefs)
        wrappingPanel.load(prefs)
        importsPanel.load(prefs)
        otherPanel.load(prefs)
        codeGenPanel.load(prefs)
        previewPanes.forEach { it.scheduleRefresh() }
    }

    /**
     * Writes all sub-panels' current state to [prefs].
     *
     * <p>If a custom scheme is currently active, the saved state is also written
     * back to [KotlinSchemeManager] so the scheme snapshot stays in sync.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        indentPanel.store(prefs)
        blankLinesPanel.store(prefs)
        spacesPanel.store(prefs)
        wrappingPanel.store(prefs)
        importsPanel.store(prefs)
        otherPanel.store(prefs)
        codeGenPanel.store(prefs)

        val customName = currentCustomSchemeName
        if (customName != null) {
            prefs.put(PREFS_KEY_CUSTOM_SCHEME, customName)
            // Keep the scheme snapshot in sync with the saved settings.
            val snapshot = CodeStyleSettings()
            KotlinCodeStylePreferences.load(prefs, snapshot)
            KotlinSchemeManager.updateScheme(customName, snapshot)
        } else {
            prefs.remove(PREFS_KEY_CUSTOM_SCHEME)
        }
    }

    private fun onSettingChanged() {
        onChange()
        previewPanes.forEach { it.scheduleRefresh() }
    }

    private fun onStyleApplied(settings: CodeStyleSettings) {
        // Update the current custom scheme name based on what the style bar selected.
        val entry = styleBar.currentEntry()
        currentCustomSchemeName = (entry as? CustomSchemeEntry)?.name

        val prefs = KotlinCodeStylePreferences.prefs()
        KotlinCodeStylePreferences.save(settings, prefs)
        load(prefs)
        onChange()
    }

    /**
     * Returns a [CodeStyleSettings] reflecting the live panel state (including any
     * unsaved changes), overlaid on the currently persisted Kotlin custom settings.
     * Used by [KotlinStyleBar] as the base when applying a style preset so that
     * fields the preset does not explicitly define are preserved.
     */
    private fun collectCurrentSettings(): CodeStyleSettings {
        val tmp = java.util.prefs.Preferences.userRoot().node("kotlin-options-collect-${System.nanoTime()}")
        collectSettingsInto(tmp)
        val settings = CodeStyleSettings()
        KotlinCodeStylePreferences.load(tmp, settings)
        return settings
    }

    private fun collectSettingsInto(prefs: Preferences) {
        // Seed with all persisted keys so style-preset fields not shown in any panel survive.
        val persisted = KotlinCodeStylePreferences.prefs()
        for (key in listOf(KotlinCodeStylePreferences.PREFS_KEY_KOTLIN, KotlinCodeStylePreferences.PREFS_KEY_COMMON)) {
            persisted.get(key, null)?.let { prefs.put(key, it) }
        }
        // Panel stores overlay their own fields on top of the base.
        indentPanel.store(prefs)
        blankLinesPanel.store(prefs)
        spacesPanel.store(prefs)
        wrappingPanel.store(prefs)
        importsPanel.store(prefs)
        otherPanel.store(prefs)
        codeGenPanel.store(prefs)
    }

    companion object {
        /** Preferences key for the name of the active custom scheme, if any. */
        const val PREFS_KEY_CUSTOM_SCHEME = "activeCustomSchemeName"

        /** General-purpose preview — used for Tabs & Indent, Spaces, Imports, Other. */
        val PREVIEW_GENERAL = """
            open class Some {
                private val f: (Int)->Int = { a: Int -> a * 2 }
                fun foo(): Int {
                    val test: Int = 12
                    for (i in 10..<42) {
                        println (when {
                            i < test -> -1
                            i > test -> 1
                            else -> 0
                        })
                    }
                    if (true) { }
                    while (true) { break }
                    try {
                        when (test) {
                            12 -> println("foo")
                            in 10..42 -> println("baz")
                            else -> println("bar")
                        }
                    } catch (e: Exception) {
                    } finally {
                    }
                    return test
                }
                private fun <T>foo2(): Int where T : List<T> {
                    return 0
                }

                fun multilineMethod(
                    foo: String,
                    bar: String
                ) {
                    foo
                        .length
                }

                fun expressionBodyMethod() =
                        "abc"
            }
            class AnotherClass<T : Any> : Some()
            """.trimIndent()

        /** Preview used on the Wrapping & Braces tab. */
        val PREVIEW_WRAPPING = """
            @Deprecated("Foo") public class ThisIsASampleClass : Comparable<*>, Appendable {
                val test =
                    12

                @Deprecated("Foo") fun foo1(i1: Int, i2: Int, i3: Int, a: Any) : Int {
                    when (i1) {
                        is Number -> 0
                        else -> 1
                    }
                    when (a) {
                        is Int,
                        is String
                        -> 0
                        else -> 1
                    }
                    if (i2 > 0 &&
                            i3 < 0) {
                        return 2
                    }
                    return 0
                }
                private fun foo2():Int {
            // todo: something
                    try {            return foo1(12, 13, 14)
                    }        catch (e: Exception) {            return 0        }        finally {           if (true) {               return 1           }           else {               return 2           }        }    }
                private val f = {a: Int->a*2}

                fun longMethod(@Named("param1") param1: Int,
                 param2: String) {
                    @Deprecated val foo = 1
                }

                fun multilineMethod(
                        foo: String,
                        bar: String?,
                        x: Int?
                    ) {
                    foo.toUpperCase().trim()
                        .length
                    val barLen = bar?.length() ?: x ?: -1
                    if (foo.length > 0 &&
                        barLen > 0) {
                        println("> 0")
                    }
                }
            }

            @Deprecated val bar = 1

            enum class Enumeration {
                A, B
            }

            fun veryLongExpressionBodyMethod() = "abc"
            """.trimIndent()

        /** Preview used on the Blank Lines tab. */
        val PREVIEW_BLANK_LINES = """
            class Foo {
               private var field1: Int = 1
               private val field2: String? = null


               init {
                   field1 = 2;
               }

               fun foo1() {
                   run {



                       field1
                   }

                   when(field1) {
                       1 -> println("1")
                       2 -> println("2")
                       3 ->
                            println("3" +
                                 "4")
                   }

                   when(field2) {
                       1 -> {
                           println("1")
                       }

                       2 -> {
                           println("2")
                       }
                   }
               }


               class InnerClass {
               }
            }



            class AnotherClass {
            }

            interface TestInterface {
            }
            fun run(f: () -> Unit) {
                f()
            }

            class Bar {
                @Annotation
                val a = 42
                @Annotation
                val b = 43
                fun c() {
                    a + b
                }
                fun d() = Unit
                // smth
                fun e() {
                    d()
                }
                fun f() = d()
            }
            """.trimIndent()
    }
}
