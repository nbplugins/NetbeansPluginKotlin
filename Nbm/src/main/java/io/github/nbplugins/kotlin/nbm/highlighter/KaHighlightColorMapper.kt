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
package io.github.nbplugins.kotlin.nbm.highlighter

import com.intellij.codeInsight.daemon.impl.HighlightInfo

/**
 * Maps IntelliJ [HighlightInfo] produced by the IDEA semantic highlighters to the
 * `FontAndColors.xml` color category name used by the NetBeans CSL API.
 *
 * The mapping is keyed on [com.intellij.openapi.editor.colors.TextAttributesKey.externalName]
 * extracted from the [HighlightInfo.type]. Unknown keys return `null` (silently ignored),
 * preserving forward compatibility when new IDEA token types are introduced.
 *
 * Returned names correspond 1:1 to `<fontcolor name="...">` entries in
 * `FontAndColors.xml` / `FontAndColorsDark.xml`, which are resolved at runtime via
 * [org.netbeans.api.editor.settings.FontColorSettings.getTokenFontColors].
 *
 * Mapping groups:
 * - **Classes**: KOTLIN_CLASS, KOTLIN_ABSTRACT_CLASS, KOTLIN_TRAIT, KOTLIN_OBJECT,
 *   KOTLIN_ENUM, KOTLIN_TYPE_ALIAS, KOTLIN_ANNOTATION, KOTLIN_TYPE_PARAMETER
 * - **Variables**: KOTLIN_LOCAL_VARIABLE, KOTLIN_PARAMETER, KOTLIN_INSTANCE_PROPERTY,
 *   KOTLIN_PACKAGE_PROPERTY, KOTLIN_EXTENSION_PROPERTY, KOTLIN_SYNTHETIC_EXTENSION_PROPERTY,
 *   KOTLIN_BACKING_FIELD_VARIABLE, KOTLIN_ENUM_ENTRY, KOTLIN_MUTABLE_VARIABLE, etc.
 * - **Functions**: KOTLIN_FUNCTION_DECLARATION, KOTLIN_FUNCTION_CALL, KOTLIN_PACKAGE_FUNCTION_CALL,
 *   KOTLIN_EXTENSION_FUNCTION_CALL, KOTLIN_CONSTRUCTOR, KOTLIN_SUSPEND_FUNCTION_CALL, etc.
 * - **Smart casts**: KOTLIN_SMART_CAST_VALUE, KOTLIN_SMART_CONSTANT, KOTLIN_SMART_CAST_RECEIVER
 * - **Misc**: KOTLIN_LABEL, KOTLIN_NAMED_ARGUMENT, KOTLIN_DEPRECATED, etc.
 */
object KaHighlightColorMapper {

    private val mappings: Map<String, String> = mapOf(
        // --- classes ---
        "KOTLIN_CLASS"                   to "KOTLIN_CLASS",
        "KOTLIN_ABSTRACT_CLASS"          to "KOTLIN_ABSTRACT_CLASS",
        "KOTLIN_TRAIT"                   to "KOTLIN_TRAIT",
        "KOTLIN_OBJECT"                  to "KOTLIN_OBJECT",
        "KOTLIN_ENUM"                    to "KOTLIN_ENUM",
        "KOTLIN_TYPE_ALIAS"              to "KOTLIN_TYPE_ALIAS",
        "KOTLIN_ANNOTATION"              to "KOTLIN_ANNOTATION",
        "KOTLIN_TYPE_PARAMETER"          to "KOTLIN_TYPE_PARAMETER",
        // --- variables ---
        "KOTLIN_LOCAL_VARIABLE"          to "KOTLIN_LOCAL_VARIABLE",
        "KOTLIN_PARAMETER"               to "KOTLIN_PARAMETER",
        "KOTLIN_WRAPPED_INTO_REF"        to "KOTLIN_WRAPPED_INTO_REF",
        "KOTLIN_INSTANCE_PROPERTY"       to "KOTLIN_INSTANCE_PROPERTY",
        "KOTLIN_PACKAGE_PROPERTY"        to "KOTLIN_PACKAGE_PROPERTY",
        "KOTLIN_EXTENSION_PROPERTY"      to "KOTLIN_EXTENSION_PROPERTY",
        "KOTLIN_SYNTHETIC_EXTENSION_PROPERTY" to "KOTLIN_SYNTHETIC_EXTENSION_PROPERTY",
        "KOTLIN_BACKING_FIELD_VARIABLE"  to "KOTLIN_BACKING_FIELD_VARIABLE",
        "KOTLIN_ENUM_ENTRY"              to "KOTLIN_ENUM_ENTRY",
        "KOTLIN_MUTABLE_VARIABLE"        to "KOTLIN_MUTABLE_VARIABLE",
        "KOTLIN_INSTANCE_PROPERTY_CUSTOM_PROPERTY_DECLARATION" to "KOTLIN_INSTANCE_PROPERTY_CUSTOM_PROPERTY_DECLARATION",
        "KOTLIN_PACKAGE_PROPERTY_CUSTOM_PROPERTY_DECLARATION"  to "KOTLIN_PACKAGE_PROPERTY_CUSTOM_PROPERTY_DECLARATION",
        "KOTLIN_CLOSURE_DEFAULT_PARAMETER"        to "KOTLIN_CLOSURE_DEFAULT_PARAMETER",
        "KOTLIN_FUNCTION_LITERAL_DEFAULT_PARAMETER" to "KOTLIN_FUNCTION_LITERAL_DEFAULT_PARAMETER",
        "KOTLIN_DYNAMIC_PROPERTY_CALL"   to "KOTLIN_DYNAMIC_PROPERTY_CALL",
        // --- functions ---
        "KOTLIN_FUNCTION_DECLARATION"    to "KOTLIN_FUNCTION_DECLARATION",
        "KOTLIN_FUNCTION_CALL"           to "KOTLIN_FUNCTION_CALL",
        "KOTLIN_PACKAGE_FUNCTION_CALL"   to "KOTLIN_PACKAGE_FUNCTION_CALL",
        "KOTLIN_EXTENSION_FUNCTION_CALL" to "KOTLIN_EXTENSION_FUNCTION_CALL",
        "KOTLIN_CONSTRUCTOR_CALL"        to "KOTLIN_CONSTRUCTOR_CALL",
        "KOTLIN_CONSTRUCTOR"             to "KOTLIN_CONSTRUCTOR",
        "KOTLIN_SUSPEND_FUNCTION_CALL"   to "KOTLIN_SUSPEND_FUNCTION_CALL",
        "KOTLIN_DYNAMIC_FUNCTION_CALL"   to "KOTLIN_DYNAMIC_FUNCTION_CALL",
        "KOTLIN_VARIABLE_AS_FUNCTION_CALL"       to "KOTLIN_VARIABLE_AS_FUNCTION_CALL",
        "KOTLIN_VARIABLE_AS_FUNCTION_LIKE_CALL"  to "KOTLIN_VARIABLE_AS_FUNCTION_LIKE_CALL",
        // --- smart casts ---
        "KOTLIN_SMART_CAST_VALUE"        to "KOTLIN_SMART_CAST_VALUE",
        "KOTLIN_SMART_CONSTANT"          to "KOTLIN_SMART_CONSTANT",
        "KOTLIN_SMART_CAST_RECEIVER"     to "KOTLIN_SMART_CAST_RECEIVER",
        // --- misc ---
        "KOTLIN_LABEL"                   to "KOTLIN_LABEL",
        "KOTLIN_NAMED_ARGUMENT"          to "KOTLIN_NAMED_ARGUMENT",
        "KOTLIN_ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES" to "KOTLIN_ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES",
        "KOTLIN_PROPERTY_WITH_BACKING_FIELD" to "KOTLIN_PROPERTY_WITH_BACKING_FIELD",
        "KOTLIN_COROUTINE_DEBUGGER_VALUE" to "KOTLIN_COROUTINE_DEBUGGER_VALUE",
        "KOTLIN_RESOLVED_TO_ERROR"       to "KOTLIN_RESOLVED_TO_ERROR",
        "KOTLIN_DEBUG_INFO"              to "KOTLIN_DEBUG_INFO",
    )

    /**
     * Converts the [TextAttributesKey][com.intellij.openapi.editor.colors.TextAttributesKey] of
     * [info]'s [HighlightInfo.type] to the `FontAndColors.xml` color category name.
     *
     * The key is read from [HighlightInfo.type] via
     * [com.intellij.codeInsight.daemon.impl.HighlightInfoType.getAttributesKey], falling back to
     * [HighlightInfo.forcedTextAttributesKey] when the type key is absent.
     *
     * @param info the highlight info produced by an IDEA semantic highlighter
     * @return the color category name, or `null` if the key is not mapped
     */
    fun toFontColorName(info: HighlightInfo): String? {
        val key = info.type.attributesKey ?: info.forcedTextAttributesKey ?: return null
        return toFontColorName(key.externalName)
    }

    /**
     * Looks up the `FontAndColors.xml` color category name by the
     * [TextAttributesKey][com.intellij.openapi.editor.colors.TextAttributesKey] external name.
     *
     * @param externalName the [com.intellij.openapi.editor.colors.TextAttributesKey.externalName]
     * @return the color category name, or `null` if the name is not mapped
     */
    internal fun toFontColorName(externalName: String): String? = mappings[externalName]

    /** Every color category name this mapper can emit, across all mapped token types. */
    internal val emittedColorNames: Set<String> get() = mappings.values.toSet()
}
