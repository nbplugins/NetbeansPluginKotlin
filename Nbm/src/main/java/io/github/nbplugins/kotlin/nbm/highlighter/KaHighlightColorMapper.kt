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
import org.netbeans.modules.csl.api.ColoringAttributes

/**
 * Maps IntelliJ [HighlightInfo] produced by the IDEA semantic highlighters to the
 * NetBeans [ColoringAttributes] sets used by the CSL coloring API.
 *
 * The mapping is keyed on [com.intellij.openapi.editor.colors.TextAttributesKey.externalName]
 * extracted from the [HighlightInfo.type]. Unknown keys are silently ignored (return `null`),
 * preserving forward compatibility when new IDEA token types are introduced.
 */
object KaHighlightColorMapper {

    private val mappings: Map<String, Set<ColoringAttributes>> = mapOf(
        // --- function declarations ---
        "KOTLIN_FUNCTION_DECLARATION"    to setOf(ColoringAttributes.DECLARATION),
        // --- function calls ---
        "KOTLIN_FUNCTION_CALL"           to setOf(ColoringAttributes.METHOD),
        "KOTLIN_PACKAGE_FUNCTION_CALL"   to setOf(ColoringAttributes.METHOD, ColoringAttributes.STATIC),
        "KOTLIN_EXTENSION_FUNCTION_CALL" to setOf(ColoringAttributes.METHOD, ColoringAttributes.STATIC),
        "KOTLIN_SUSPEND_FUNCTION_CALL"   to setOf(ColoringAttributes.METHOD, ColoringAttributes.CUSTOM1),
        "KOTLIN_CONSTRUCTOR_CALL"        to setOf(ColoringAttributes.METHOD),
        "KOTLIN_CONSTRUCTOR"             to setOf(ColoringAttributes.METHOD),
        "KOTLIN_VARIABLE_AS_FUNCTION_CALL"      to setOf(ColoringAttributes.METHOD),
        "KOTLIN_VARIABLE_AS_FUNCTION_LIKE_CALL" to setOf(ColoringAttributes.METHOD),
        "KOTLIN_DYNAMIC_FUNCTION_CALL"   to setOf(ColoringAttributes.METHOD),
        // --- class kinds ---
        "KOTLIN_CLASS"                   to setOf(ColoringAttributes.CLASS),
        "KOTLIN_ABSTRACT_CLASS"          to setOf(ColoringAttributes.CLASS),
        "KOTLIN_TRAIT"                   to setOf(ColoringAttributes.INTERFACE),
        "KOTLIN_OBJECT"                  to setOf(ColoringAttributes.CLASS),
        "KOTLIN_ENUM"                    to setOf(ColoringAttributes.CLASS),
        "KOTLIN_ANNOTATION"              to setOf(ColoringAttributes.ANNOTATION_TYPE),
        "KOTLIN_TYPE_ALIAS"              to setOf(ColoringAttributes.CLASS),
        "KOTLIN_TYPE_PARAMETER"          to setOf(ColoringAttributes.TYPE_PARAMETER_USE),
        // --- variables: ENUM (bold) matches KotlinHighlightingAttributes.FINAL_FIELD / STATIC_FINAL_FIELD;
        //     MUTABLE_VARIABLE overlay adds CUSTOM2 on top to produce FIELD / STATIC_FIELD ---
        "KOTLIN_LOCAL_VARIABLE"          to setOf(ColoringAttributes.LOCAL_VARIABLE),
        "KOTLIN_PARAMETER"               to setOf(ColoringAttributes.PARAMETER),
        "KOTLIN_INSTANCE_PROPERTY"       to setOf(ColoringAttributes.FIELD, ColoringAttributes.ENUM),
        "KOTLIN_PACKAGE_PROPERTY"        to setOf(ColoringAttributes.FIELD, ColoringAttributes.STATIC, ColoringAttributes.ENUM),
        "KOTLIN_EXTENSION_PROPERTY"      to setOf(ColoringAttributes.FIELD, ColoringAttributes.STATIC, ColoringAttributes.ENUM),
        "KOTLIN_SYNTHETIC_EXTENSION_PROPERTY" to setOf(ColoringAttributes.FIELD, ColoringAttributes.STATIC, ColoringAttributes.ENUM),
        "KOTLIN_BACKING_FIELD_VARIABLE"  to setOf(ColoringAttributes.FIELD, ColoringAttributes.ENUM),
        "KOTLIN_MUTABLE_VARIABLE"        to setOf(ColoringAttributes.CUSTOM2),
        "KOTLIN_ENUM_ENTRY"              to setOf(ColoringAttributes.FIELD, ColoringAttributes.STATIC, ColoringAttributes.ENUM),
        "KOTLIN_INSTANCE_PROPERTY_CUSTOM_PROPERTY_DECLARATION" to setOf(ColoringAttributes.FIELD, ColoringAttributes.ENUM),
        "KOTLIN_PACKAGE_PROPERTY_CUSTOM_PROPERTY_DECLARATION"  to setOf(ColoringAttributes.FIELD, ColoringAttributes.STATIC, ColoringAttributes.ENUM),
        "KOTLIN_CLOSURE_DEFAULT_PARAMETER" to setOf(ColoringAttributes.PARAMETER),
        "KOTLIN_FUNCTION_LITERAL_DEFAULT_PARAMETER" to setOf(ColoringAttributes.PARAMETER),
    )

    /**
     * Converts the [TextAttributesKey][com.intellij.openapi.editor.colors.TextAttributesKey] of
     * [info]'s [HighlightInfo.type] to a [ColoringAttributes] set.
     *
     * The key is read from [HighlightInfo.type] via
     * [com.intellij.codeInsight.daemon.impl.HighlightInfoType.getAttributesKey], falling back to
     * [HighlightInfo.forcedTextAttributesKey] when the type key is absent.
     *
     * @param info the highlight info produced by an IDEA semantic highlighter
     * @return the corresponding [ColoringAttributes] set, or `null` if the key is not mapped
     */
    fun toColoringAttributes(info: HighlightInfo): Set<ColoringAttributes>? {
        val key = info.type.attributesKey ?: info.forcedTextAttributesKey ?: return null
        return toColoringAttributes(key.externalName)
    }

    /**
     * Looks up [ColoringAttributes] by the [TextAttributesKey] external name string directly.
     *
     * @param externalName the [com.intellij.openapi.editor.colors.TextAttributesKey.externalName]
     * @return the corresponding [ColoringAttributes] set, or `null` if the name is not mapped
     */
    internal fun toColoringAttributes(externalName: String): Set<ColoringAttributes>? =
        mappings[externalName]
}
