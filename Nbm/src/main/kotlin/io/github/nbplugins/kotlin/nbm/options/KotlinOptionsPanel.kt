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
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterBlankLinesPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterImportsPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterIndentPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterOtherPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormattingPreviewPane
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinSettingsTreePanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinStyleBar
import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.event.ChangeListener

/**
 * Root panel for Tools → Options → Kotlin.
 *
 * <p>Layout: a [KotlinStyleBar] (code-style preset selector) at the top, with
 * a [JSplitPane] below that shows the formatter tabs on the left and a live
 * [KotlinFormattingPreviewPane] on the right.
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
    private val codeGenPanel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.COMMENTER_SETTINGS, ::onSettingChanged)

    private val previewPane = KotlinFormattingPreviewPane(::collectSettingsInto)
    private val styleBar = KotlinStyleBar(::onStyleApplied, ::collectCurrentSettings)

    private val tabs = JTabbedPane()
    private val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, previewPane).apply {
        resizeWeight = 0.55
    }

    init {
        tabs.addTab("Tabs & Indent", indentPanel)
        tabs.addTab("Spaces", spacesPanel)
        tabs.addTab("Wrapping & Braces", wrappingPanel)
        tabs.addTab("Blank Lines", blankLinesPanel)
        tabs.addTab("Imports", importsPanel)
        tabs.addTab("Other", otherPanel)
        tabs.addTab("Code Generation", codeGenPanel)
        tabs.addChangeListener(ChangeListener {
            previewPane.setSource(previewForTab(tabs.getTitleAt(tabs.selectedIndex)))
        })
        add(styleBar, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
    }

    override fun addNotify() {
        super.addNotify()
        splitPane.setDividerLocation(0.55)
    }

    /**
     * Populates all sub-panels from [prefs] and syncs the style bar.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        styleBar.setCurrentStyle(tmp.kotlinCustomSettings.CODE_STYLE_DEFAULTS)
        indentPanel.load(prefs)
        blankLinesPanel.load(prefs)
        spacesPanel.load(prefs)
        wrappingPanel.load(prefs)
        importsPanel.load(prefs)
        otherPanel.load(prefs)
        codeGenPanel.load(prefs)
        previewPane.setSource(previewForTab(tabs.getTitleAt(tabs.selectedIndex)))
    }

    /**
     * Writes all sub-panels' current state to [prefs].
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
    }

    private fun onSettingChanged() {
        onChange()
        previewPane.scheduleRefresh()
    }

    private fun onStyleApplied(settings: CodeStyleSettings) {
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

    /** Returns the appropriate preview source for the given tab title. */
    private fun previewForTab(title: String): String = when (title) {
        "Wrapping & Braces" -> PREVIEW_WRAPPING
        "Blank Lines" -> PREVIEW_BLANK_LINES
        else -> PREVIEW_GENERAL
    }

    companion object {
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
