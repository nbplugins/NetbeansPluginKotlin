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

import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings

/**
 * Implements [CodeStyleSettingsCustomizable] to collect option definitions emitted by
 * [org.jetbrains.kotlin.idea.formatter.KotlinLanguageCodeStyleSettingsProvider.customizeSettings].
 *
 * <p>After [build] is called, [optionGroups] contains [OptionGroup] / [OptionItem] instances
 * with read/write lambdas bound to the live [CommonCodeStyleSettings] and
 * [KotlinCodeStyleSettings] objects supplied via [commonProvider] and [kotlinProvider].
 * The lambdas use reflection so they remain valid across [load] calls (the provider
 * lambdas are evaluated lazily on each get/set).
 *
 * @param settingsTypeName  identifier for the settings type; used only for logging
 * @param commonProvider   returns the live [CommonCodeStyleSettings] for the Kotlin language
 * @param kotlinProvider   returns the live [KotlinCodeStyleSettings]
 */
class KotlinStyleOptionsCollector(
    private val settingsTypeName: String,
    private val commonProvider: () -> CommonCodeStyleSettings,
    private val kotlinProvider: () -> KotlinCodeStyleSettings
) : CodeStyleSettingsCustomizable {

    /** Accumulated definition entries in insertion order. */
    private val entries = mutableListOf<EntryDef>()

    /** Pending renames: key → new display string. Key is either a field name or group identifier. */
    private val renames = mutableMapOf<String, String>()

    /** Mapping from standard option field name → default group identifier. */
    private val standardGroups = STANDARD_GROUPS

    /**
     * Receives standard [CommonCodeStyleSettings] field names.
     * Group is determined from [STANDARD_GROUPS]; unknown options land in a fallback group.
     */
    override fun showStandardOptions(vararg optionNames: String) {
        for (name in optionNames) {
            val group = standardGroups[name] ?: "General"
            entries.add(EntryDef(name, null, group, isCustom = false))
        }
    }

    /**
     * Receives a custom [KotlinCodeStyleSettings] field definition.
     *
     * @param settingsClass expected to be [KotlinCodeStyleSettings]::class.java
     * @param fieldName     field on [KotlinCodeStyleSettings]
     * @param title         display label
     * @param groupName     group header string (null → use title as group, so wrap items act as section headers)
     * @param options       optional [String[], int[]] pair for wrap combobox items
     */
    override fun showCustomOption(
        settingsClass: Class<*>,
        fieldName: String,
        title: String,
        groupName: String?,
        vararg options: Any
    ) {
        // When groupName is null, the item is itself a section header (typically a WrapItem).
        // Use the title as the group ID so sub-items placed in a group named after this title
        // are co-located with the wrap header.
        val effectiveGroup = groupName ?: title
        entries.add(EntryDef(fieldName, title, effectiveGroup, isCustom = true, extraOptions = options))
    }

    /**
     * Renames a group or individual item identified by [fieldOrGroupName].
     * Applied during [build].
     */
    override fun renameStandardOption(fieldOrGroupName: String, newTitle: String) {
        renames[fieldOrGroupName] = newTitle
    }

    /**
     * Returns the list of field-name entries accumulated by [showStandardOptions] and
     * [showCustomOption]. Used by [KotlinSettingsTreePanel] to identify which fields
     * this panel owns when performing merge-store.
     */
    fun fieldEntries(): List<FieldEntry> = entries.map { FieldEntry(it.fieldName, it.isCustom) }

    /**
     * Constructs [OptionGroup] / [OptionItem] lists from the accumulated definitions.
     * Renames are applied, standard option titles resolved, and groups ordered by
     * first-occurrence.
     *
     * @return ordered list of [OptionGroup]s ready for [OptionTreePanel]
     */
    fun build(): List<OptionGroup> {
        val groupMap = linkedMapOf<String, MutableList<OptionItem>>()

        for (entry in entries) {
            // Resolve group: (1) explicit rename via renameStandardOption, (2) Kotlin-bundle alias
            // mapping IDEA's standard group constants ("Method …") to Kotlin's renamed labels
            // ("Function …") so custom options emitted under standard group constants merge into
            // the same tree group as the renamed standard options.
            val resolvedGroupId = renames[entry.groupId]
                ?: GROUP_ALIASES[entry.groupId]
                ?: entry.groupId
            val groupItems = groupMap.getOrPut(resolvedGroupId) { mutableListOf() }

            val item = entry.toOptionItem() ?: continue
            groupItems.add(item)
        }

        return groupMap.map { (groupName, items) -> OptionGroup(groupName, items) }.filter { it.items.isNotEmpty() }
    }

    // ---- internal ----

    private inner class EntryDef(
        val fieldName: String,
        val explicitTitle: String?,
        val groupId: String,
        val isCustom: Boolean,
        val extraOptions: Array<out Any> = emptyArray()
    ) {
        fun toOptionItem(): OptionItem? {
            val title = renames[fieldName] ?: explicitTitle ?: standardTitles[fieldName] ?: fieldName

            return if (isCustom) buildCustomItem(title)
            else buildStandardItem(title)
        }

        private fun buildCustomItem(title: String): OptionItem? {
            val cls = KotlinCodeStyleSettings::class.java
            val field = runCatching { cls.getField(fieldName) }.getOrNull() ?: return null
            val wrapValues = (extraOptions.getOrNull(1) as? IntArray) ?: IntArray(0)
            return if (wrapValues.isNotEmpty()) {
                val displayOptions = (extraOptions.getOrNull(0) as? Array<*>)
                    ?.filterIsInstance<String>()?.toTypedArray()
                    ?: CodeStyleSettingsCustomizable.WRAP_OPTIONS
                WrapItem(title, displayOptions, wrapValues,
                    get = { field.getInt(kotlinProvider()) },
                    set = { v -> field.setInt(kotlinProvider(), v) })
            } else {
                BoolItem(title,
                    get = { field.getBoolean(kotlinProvider()) },
                    set = { v -> field.setBoolean(kotlinProvider(), v) })
            }
        }

        private fun buildStandardItem(title: String): OptionItem? {
            val cls = CommonCodeStyleSettings::class.java
            val field = runCatching { cls.getField(fieldName) }.getOrNull() ?: return null
            return when {
                WRAP_FIELDS.contains(fieldName) ->
                    WrapItem(title, CodeStyleSettingsCustomizable.WRAP_OPTIONS, CodeStyleSettingsCustomizable.WRAP_VALUES,
                        get = { field.getInt(commonProvider()) },
                        set = { v -> field.setInt(commonProvider(), v) })
                field.type == java.lang.Integer.TYPE -> null  // int non-wrap fields (e.g. RIGHT_MARGIN) not supported
                else ->
                    BoolItem(title,
                        get = { field.getBoolean(commonProvider()) },
                        set = { v -> field.setBoolean(commonProvider(), v) })
            }
        }
    }

    /** Identifies a single field handled by the collector; used for merge-store in [KotlinSettingsTreePanel]. */
    data class FieldEntry(val fieldName: String, val isCustom: Boolean)

    companion object {
        /**
         * Maps standard [CommonCodeStyleSettings] field names to their display group name.
         * Group names match [com.intellij.psi.codeStyle.CodeStyleSettingsCustomizableOptions] constants
         * so that standard and custom options for the same section end up in the same tree group.
         */
        val STANDARD_GROUPS: Map<String, String> = mapOf(
            // Spaces — around operators (matches CodeStyleSettingsCustomizableOptions.SPACES_AROUND_OPERATORS)
            "SPACE_AROUND_ASSIGNMENT_OPERATORS"     to "Around operators",
            "SPACE_AROUND_LOGICAL_OPERATORS"        to "Around operators",
            "SPACE_AROUND_EQUALITY_OPERATORS"       to "Around operators",
            "SPACE_AROUND_RELATIONAL_OPERATORS"     to "Around operators",
            "SPACE_AROUND_ADDITIVE_OPERATORS"       to "Around operators",
            "SPACE_AROUND_MULTIPLICATIVE_OPERATORS" to "Around operators",
            "SPACE_AROUND_UNARY_OPERATOR"           to "Around operators",
            // Spaces — before parentheses (matches SPACES_BEFORE_PARENTHESES)
            "SPACE_BEFORE_IF_PARENTHESES"   to "Before parentheses",
            "SPACE_BEFORE_WHILE_PARENTHESES" to "Before parentheses",
            "SPACE_BEFORE_FOR_PARENTHESES"  to "Before parentheses",
            "SPACE_BEFORE_CATCH_PARENTHESES" to "Before parentheses",
            // Spaces — other (matches SPACES_OTHER)
            "SPACE_AFTER_COMMA"  to "Other",
            "SPACE_BEFORE_COMMA" to "Other",
            // Wrapping — keep when reformatting (matches WRAPPING_KEEP)
            "KEEP_LINE_BREAKS"          to "Keep when reformatting",
            "KEEP_FIRST_COLUMN_COMMENT" to "Keep when reformatting",
            // Wrapping — right margin (int fields, skipped by buildStandardItem)
            "RIGHT_MARGIN"   to "Right margin",
            "WRAP_ON_TYPING" to "Right margin",
            // Wrapping — function declaration parameters (matches WRAPPING_METHOD_PARAMETERS)
            "METHOD_PARAMETERS_WRAP"                to "Function declaration parameters",
            "ALIGN_MULTILINE_PARAMETERS"            to "Function declaration parameters",
            "METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE" to "Function declaration parameters",
            "METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE" to "Function declaration parameters",
            // Wrapping — function parentheses (matches WRAPPING_METHOD_PARENTHESES; IDEA renames to "Function parentheses")
            "ALIGN_MULTILINE_METHOD_BRACKETS" to "Method parentheses",
            // Wrapping — function call arguments (matches WRAPPING_METHOD_ARGUMENTS_WRAPPING)
            "CALL_PARAMETERS_WRAP"                to "Function call arguments",
            "ALIGN_MULTILINE_PARAMETERS_IN_CALLS" to "Function call arguments",
            "CALL_PARAMETERS_LPAREN_ON_NEXT_LINE" to "Function call arguments",
            "CALL_PARAMETERS_RPAREN_ON_NEXT_LINE" to "Function call arguments",
            // Wrapping — chained calls (matches WRAPPING_CALL_CHAIN)
            "METHOD_CALL_CHAIN_WRAP"          to "Chained function calls",
            "WRAP_FIRST_METHOD_IN_CALL_CHAIN" to "Chained function calls",
            // Wrapping — else/catch/finally/while route to IDEA's standard control-statement groups
            "ELSE_ON_NEW_LINE"    to "'if()' statement",
            "WHILE_ON_NEW_LINE"   to "do...while() statement",
            "CATCH_ON_NEW_LINE"   to "try statement(s)",
            "FINALLY_ON_NEW_LINE" to "try statement(s)",
            // Wrapping — binary operations (matches WRAPPING_BINARY_OPERATION)
            "ALIGN_MULTILINE_BINARY_OPERATION" to "Binary operations",
            // Wrapping — extends list (matches WRAPPING_EXTENDS_LIST)
            "EXTENDS_LIST_WRAP"            to "Extends/implements list",
            "ALIGN_MULTILINE_EXTENDS_LIST" to "Extends/implements list",
            // Wrapping — assignment (matches WRAPPING_ASSIGNMENT)
            "ASSIGNMENT_WRAP" to "Assignment statement",
            // Wrapping — enum constants
            "ENUM_CONSTANTS_WRAP" to "Enum constants",
            // Wrapping — annotations
            "METHOD_ANNOTATION_WRAP"    to "Annotations",
            "CLASS_ANNOTATION_WRAP"     to "Annotations",
            "PARAMETER_ANNOTATION_WRAP" to "Annotations",
            "VARIABLE_ANNOTATION_WRAP"  to "Annotations",
            "FIELD_ANNOTATION_WRAP"     to "Annotations",
            // Blank lines (matches BLANK_LINES)
            "KEEP_BLANK_LINES_IN_CODE"         to "Minimum blank lines",
            "KEEP_BLANK_LINES_IN_DECLARATIONS" to "Minimum blank lines",
            "KEEP_BLANK_LINES_BEFORE_RBRACE"   to "Minimum blank lines",
            "BLANK_LINES_AFTER_CLASS_HEADER"   to "Minimum blank lines",
            // Commenter settings (Code Generation tab)
            "LINE_COMMENT_AT_FIRST_COLUMN"       to "Comment Code",
            "LINE_COMMENT_ADD_SPACE"             to "Comment Code",
            "LINE_COMMENT_ADD_SPACE_ON_REFORMAT" to "Comment Code",
            "BLOCK_COMMENT_AT_FIRST_COLUMN"      to "Comment Code",
            "BLOCK_COMMENT_ADD_SPACE"            to "Comment Code"
        )

        /**
         * Maps IDEA's standard group constants (`CodeStyleSettingsCustomizableOptions.WRAPPING_*`)
         * to Kotlin's renamed labels. Used to merge custom options emitted with the IDEA constant
         * as `groupName` into the same tree group as the renamed standard options. Kotlin's
         * `KotlinLanguageCodeStyleSettingsProvider` does not call `renameStandardOption` on the
         * group constants themselves for these three, so we apply the rename ourselves.
         */
        val GROUP_ALIASES: Map<String, String> = mapOf(
            "Method declaration parameters" to "Function declaration parameters",
            "Method call arguments"         to "Function call arguments",
            "Chained method calls"          to "Chained function calls"
        )

        /** Standard option field names that are int wrap-mode fields (not boolean). */
        val WRAP_FIELDS: Set<String> = setOf(
            "METHOD_PARAMETERS_WRAP", "CALL_PARAMETERS_WRAP",
            "METHOD_CALL_CHAIN_WRAP", "EXTENDS_LIST_WRAP",
            "ASSIGNMENT_WRAP", "ENUM_CONSTANTS_WRAP",
            "METHOD_ANNOTATION_WRAP", "CLASS_ANNOTATION_WRAP",
            "PARAMETER_ANNOTATION_WRAP", "FIELD_ANNOTATION_WRAP", "VARIABLE_ANNOTATION_WRAP"
        )

        /** Display titles for standard options (from IntelliJ ApplicationBundle / UX copy). */
        val standardTitles: Map<String, String> = mapOf(
            "SPACE_AROUND_ASSIGNMENT_OPERATORS"    to "Assignment operators (=, +=, ...)",
            "SPACE_AROUND_LOGICAL_OPERATORS"       to "Logical operators (&&, ||)",
            "SPACE_AROUND_EQUALITY_OPERATORS"      to "Equality operators (==, !=)",
            "SPACE_AROUND_RELATIONAL_OPERATORS"    to "Relational operators (<, >, <=, >=)",
            "SPACE_AROUND_ADDITIVE_OPERATORS"      to "Additive operators (+, -)",
            "SPACE_AROUND_MULTIPLICATIVE_OPERATORS" to "Multiplicative operators (*, /, %)",
            "SPACE_AROUND_UNARY_OPERATOR"          to "Unary operators (!, -, +, ++, --)",
            "SPACE_BEFORE_IF_PARENTHESES"          to "'if' parentheses",
            "SPACE_BEFORE_WHILE_PARENTHESES"       to "'while' parentheses",
            "SPACE_BEFORE_FOR_PARENTHESES"         to "'for' parentheses",
            "SPACE_BEFORE_CATCH_PARENTHESES"       to "'catch' parentheses",
            "SPACE_AFTER_COMMA"                    to "After comma",
            "SPACE_BEFORE_COMMA"                   to "Before comma",
            "KEEP_LINE_BREAKS"                     to "Line breaks",
            "KEEP_FIRST_COLUMN_COMMENT"            to "Comment at first column",
            "RIGHT_MARGIN"                         to "Right margin (columns)",
            "WRAP_ON_TYPING"                       to "Wrap on typing",
            "METHOD_PARAMETERS_WRAP"               to "Function declaration parameters",
            "ALIGN_MULTILINE_PARAMETERS"           to "Align when multiline",
            "METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE" to "New line after '('",
            "METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE" to "Place ')' on new line",
            "ALIGN_MULTILINE_METHOD_BRACKETS"      to "Align when multiline",
            "CALL_PARAMETERS_WRAP"                 to "Function call arguments",
            "ALIGN_MULTILINE_PARAMETERS_IN_CALLS"  to "Align when multiline",
            "CALL_PARAMETERS_LPAREN_ON_NEXT_LINE"  to "New line after '('",
            "CALL_PARAMETERS_RPAREN_ON_NEXT_LINE"  to "Place ')' on new line",
            "METHOD_CALL_CHAIN_WRAP"               to "Chained function calls",
            "WRAP_FIRST_METHOD_IN_CALL_CHAIN"      to "Wrap first call",
            "ELSE_ON_NEW_LINE"                     to "'else' on new line",
            "WHILE_ON_NEW_LINE"                    to "'while' on new line",
            "CATCH_ON_NEW_LINE"                    to "'catch' on new line",
            "FINALLY_ON_NEW_LINE"                  to "'finally' on new line",
            "ALIGN_MULTILINE_BINARY_OPERATION"     to "Align when multiline",
            "EXTENDS_LIST_WRAP"                    to "Extends/implements list",
            "ALIGN_MULTILINE_EXTENDS_LIST"         to "Align when multiline",
            "ASSIGNMENT_WRAP"                      to "Assignment",
            "ENUM_CONSTANTS_WRAP"                  to "Enum constants",
            "METHOD_ANNOTATION_WRAP"               to "Function annotations",
            "CLASS_ANNOTATION_WRAP"                to "Class annotations",
            "PARAMETER_ANNOTATION_WRAP"            to "Parameter annotations",
            "VARIABLE_ANNOTATION_WRAP"             to "Variable annotations",
            "FIELD_ANNOTATION_WRAP"                to "Property annotations",
            "KEEP_BLANK_LINES_IN_CODE"             to "In code",
            "KEEP_BLANK_LINES_IN_DECLARATIONS"     to "In declarations",
            "KEEP_BLANK_LINES_BEFORE_RBRACE"       to "Before '}'",
            "BLANK_LINES_AFTER_CLASS_HEADER"       to "After class header",
            "LINE_COMMENT_AT_FIRST_COLUMN"         to "Line comment at first column",
            "LINE_COMMENT_ADD_SPACE"               to "Add a space at comment start",
            "LINE_COMMENT_ADD_SPACE_ON_REFORMAT"   to "Reformat when adding a space",
            "BLOCK_COMMENT_AT_FIRST_COLUMN"        to "Block comment at first column",
            "BLOCK_COMMENT_ADD_SPACE"              to "Add spaces around block comment text"
        )
    }
}
