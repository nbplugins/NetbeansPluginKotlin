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
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.util.prefs.Preferences
import javax.swing.JPanel

/**
 * Sub-panel for the "Spaces" tab in Tools → Options → Kotlin.
 *
 * <p>Shows an [OptionTreePanel] with boolean checkboxes organized into groups
 * matching IDEA's Kotlin code-style Spaces panel. Reads and writes both
 * [com.intellij.psi.codeStyle.CommonCodeStyleSettings] (standard fields) and
 * [org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings] (custom fields).
 *
 * @param onChange called whenever any control changes value
 */
class KotlinFormatterSpacesPanel(private val onChange: () -> Unit) : JPanel() {

    /** True while load() is populating controls; suppresses onChange callbacks. */
    private var isLoading = false

    private val fireChange: () -> Unit = { if (!isLoading) onChange() }

    // Backing variables for all options. Populated during load(), read during store().
    private var spaceAroundRange = false
    private var spaceAroundAssignment = true
    private var spaceAroundLogical = true
    private var spaceAroundEquality = true
    private var spaceAroundRelational = true
    private var spaceAroundAdditive = true
    private var spaceAroundMultiplicative = true
    private var spaceAroundUnary = false
    private var spaceAroundFunctionTypeArrow = true
    private var spaceAroundWhenArrow = true
    private var spaceAfterComma = true
    private var spaceBeforeComma = false
    private var spaceBeforeIf = true
    private var spaceBeforeWhile = true
    private var spaceBeforeFor = true
    private var spaceBeforeCatch = true
    private var spaceBeforeWhen = true
    private var spaceBeforeLambdaArrow = true
    private var spaceBeforeTypeColon = false
    private var spaceAfterTypeColon = true
    private var spaceBeforeExtendColon = true
    private var spaceAfterExtendColon = true
    private var insertWhitespacesInSimpleMethod = true

    private val treePanel = OptionTreePanel(buildGroups(), fireChange)

    init {
        layout = java.awt.BorderLayout()
        add(treePanel, java.awt.BorderLayout.CENTER)
    }

    private fun buildGroups(): List<OptionGroup> = listOf(
        OptionGroup("Before parentheses", listOf(
            BoolItem("'if' parentheses", { spaceBeforeIf }, { spaceBeforeIf = it; fireChange() }),
            BoolItem("'for' parentheses", { spaceBeforeFor }, { spaceBeforeFor = it; fireChange() }),
            BoolItem("'while' parentheses", { spaceBeforeWhile }, { spaceBeforeWhile = it; fireChange() }),
            BoolItem("'catch' parentheses", { spaceBeforeCatch }, { spaceBeforeCatch = it; fireChange() }),
            BoolItem("'when' parentheses", { spaceBeforeWhen }, { spaceBeforeWhen = it; fireChange() }),
        )),
        OptionGroup("Around operators", listOf(
            BoolItem("Assignment operators (=, +=, ...)", { spaceAroundAssignment }, { spaceAroundAssignment = it; fireChange() }),
            BoolItem("Logical operators (&&, ||)", { spaceAroundLogical }, { spaceAroundLogical = it; fireChange() }),
            BoolItem("Equality operators (==, !=)", { spaceAroundEquality }, { spaceAroundEquality = it; fireChange() }),
            BoolItem("Relational operators (<, >, <=, >=)", { spaceAroundRelational }, { spaceAroundRelational = it; fireChange() }),
            BoolItem("Additive operators (+, -)", { spaceAroundAdditive }, { spaceAroundAdditive = it; fireChange() }),
            BoolItem("Multiplicative operators (*, /, %)", { spaceAroundMultiplicative }, { spaceAroundMultiplicative = it; fireChange() }),
            BoolItem("Unary operators (!, -, +, ++, --)", { spaceAroundUnary }, { spaceAroundUnary = it; fireChange() }),
            BoolItem("Range operators (.., ..<)", { spaceAroundRange }, { spaceAroundRange = it; fireChange() }),
        )),
        OptionGroup("Other", listOf(
            BoolItem("Before comma", { spaceBeforeComma }, { spaceBeforeComma = it; fireChange() }),
            BoolItem("After comma", { spaceAfterComma }, { spaceAfterComma = it; fireChange() }),
            BoolItem("Before colon, after declaration name", { spaceBeforeTypeColon }, { spaceBeforeTypeColon = it; fireChange() }),
            BoolItem("After colon, before declaration type", { spaceAfterTypeColon }, { spaceAfterTypeColon = it; fireChange() }),
            BoolItem("Before colon in new type definition", { spaceBeforeExtendColon }, { spaceBeforeExtendColon = it; fireChange() }),
            BoolItem("After colon in new type definition", { spaceAfterExtendColon }, { spaceAfterExtendColon = it; fireChange() }),
            BoolItem("In simple one line methods", { insertWhitespacesInSimpleMethod }, { insertWhitespacesInSimpleMethod = it; fireChange() }),
            BoolItem("Around arrow in function types", { spaceAroundFunctionTypeArrow }, { spaceAroundFunctionTypeArrow = it; fireChange() }),
            BoolItem("Around arrow in \"when\" clause", { spaceAroundWhenArrow }, { spaceAroundWhenArrow = it; fireChange() }),
            BoolItem("Before lambda arrow", { spaceBeforeLambdaArrow }, { spaceBeforeLambdaArrow = it; fireChange() }),
        )),
    )

    /**
     * Populates all controls from [prefs] by deserializing the persisted settings.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        isLoading = true
        try {
            val cs = tmp.getCommonSettings(KotlinLanguage.INSTANCE)
            val ks = tmp.kotlinCustomSettings
            spaceAroundRange = ks.SPACE_AROUND_RANGE
            spaceAroundAssignment = cs.SPACE_AROUND_ASSIGNMENT_OPERATORS
            spaceAroundLogical = cs.SPACE_AROUND_LOGICAL_OPERATORS
            spaceAroundEquality = cs.SPACE_AROUND_EQUALITY_OPERATORS
            spaceAroundRelational = cs.SPACE_AROUND_RELATIONAL_OPERATORS
            spaceAroundAdditive = cs.SPACE_AROUND_ADDITIVE_OPERATORS
            spaceAroundMultiplicative = cs.SPACE_AROUND_MULTIPLICATIVE_OPERATORS
            spaceAroundUnary = cs.SPACE_AROUND_UNARY_OPERATOR
            spaceAroundFunctionTypeArrow = ks.SPACE_AROUND_FUNCTION_TYPE_ARROW
            spaceAroundWhenArrow = ks.SPACE_AROUND_WHEN_ARROW
            spaceAfterComma = cs.SPACE_AFTER_COMMA
            spaceBeforeComma = cs.SPACE_BEFORE_COMMA
            spaceBeforeIf = cs.SPACE_BEFORE_IF_PARENTHESES
            spaceBeforeWhile = cs.SPACE_BEFORE_WHILE_PARENTHESES
            spaceBeforeFor = cs.SPACE_BEFORE_FOR_PARENTHESES
            spaceBeforeCatch = cs.SPACE_BEFORE_CATCH_PARENTHESES
            spaceBeforeWhen = ks.SPACE_BEFORE_WHEN_PARENTHESES
            spaceBeforeLambdaArrow = ks.SPACE_BEFORE_LAMBDA_ARROW
            spaceBeforeTypeColon = ks.SPACE_BEFORE_TYPE_COLON
            spaceAfterTypeColon = ks.SPACE_AFTER_TYPE_COLON
            spaceBeforeExtendColon = ks.SPACE_BEFORE_EXTEND_COLON
            spaceAfterExtendColon = ks.SPACE_AFTER_EXTEND_COLON
            insertWhitespacesInSimpleMethod = ks.INSERT_WHITESPACES_IN_SIMPLE_ONE_LINE_METHOD
        } finally {
            isLoading = false
        }
    }

    /**
     * Writes the controls' current state to [prefs], merging with any existing settings so
     * that other fields (e.g. Wrapping panel settings) are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        val cs = tmp.getCommonSettings(KotlinLanguage.INSTANCE)
        val ks = tmp.kotlinCustomSettings
        ks.SPACE_AROUND_RANGE = spaceAroundRange
        cs.SPACE_AROUND_ASSIGNMENT_OPERATORS = spaceAroundAssignment
        cs.SPACE_AROUND_LOGICAL_OPERATORS = spaceAroundLogical
        cs.SPACE_AROUND_EQUALITY_OPERATORS = spaceAroundEquality
        cs.SPACE_AROUND_RELATIONAL_OPERATORS = spaceAroundRelational
        cs.SPACE_AROUND_ADDITIVE_OPERATORS = spaceAroundAdditive
        cs.SPACE_AROUND_MULTIPLICATIVE_OPERATORS = spaceAroundMultiplicative
        cs.SPACE_AROUND_UNARY_OPERATOR = spaceAroundUnary
        ks.SPACE_AROUND_FUNCTION_TYPE_ARROW = spaceAroundFunctionTypeArrow
        ks.SPACE_AROUND_WHEN_ARROW = spaceAroundWhenArrow
        cs.SPACE_AFTER_COMMA = spaceAfterComma
        cs.SPACE_BEFORE_COMMA = spaceBeforeComma
        cs.SPACE_BEFORE_IF_PARENTHESES = spaceBeforeIf
        cs.SPACE_BEFORE_WHILE_PARENTHESES = spaceBeforeWhile
        cs.SPACE_BEFORE_FOR_PARENTHESES = spaceBeforeFor
        cs.SPACE_BEFORE_CATCH_PARENTHESES = spaceBeforeCatch
        ks.SPACE_BEFORE_WHEN_PARENTHESES = spaceBeforeWhen
        ks.SPACE_BEFORE_LAMBDA_ARROW = spaceBeforeLambdaArrow
        ks.SPACE_BEFORE_TYPE_COLON = spaceBeforeTypeColon
        ks.SPACE_AFTER_TYPE_COLON = spaceAfterTypeColon
        ks.SPACE_BEFORE_EXTEND_COLON = spaceBeforeExtendColon
        ks.SPACE_AFTER_EXTEND_COLON = spaceAfterExtendColon
        ks.INSERT_WHITESPACES_IN_SIMPLE_ONE_LINE_METHOD = insertWhitespacesInSimpleMethod
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    // ─── Test-helper accessors ─────────────────────────────────────────────────

    /** Returns whether the "Around assignment operators" option is selected. */
    fun isSpaceAroundAssignmentOperators(): Boolean = spaceAroundAssignment
    /** Returns whether the "Around range operator" option is selected. */
    fun isSpaceAroundRange(): Boolean = spaceAroundRange
    /** Returns whether the "Before 'if' parentheses" option is selected. */
    fun isSpaceBeforeIfParentheses(): Boolean = spaceBeforeIf

    /** Sets the "Around assignment operators" option without firing onChange. */
    fun setSpaceAroundAssignmentOperators(v: Boolean) { spaceAroundAssignment = v }
}
