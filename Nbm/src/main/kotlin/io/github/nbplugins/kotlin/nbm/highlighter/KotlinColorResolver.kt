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
package io.github.nbplugins.kotlin.nbm.highlighter

import javax.swing.text.AttributeSet
import javax.swing.text.StyleConstants
import org.netbeans.api.editor.mimelookup.MimeLookup
import org.netbeans.api.editor.settings.FontColorSettings

/**
 * Resolves `FontAndColors.xml` color category names to runtime [AttributeSet]s and
 * CSS hex color strings, consulting the currently active user theme.
 *
 * Color category names (e.g. `"KOTLIN_FUNCTION_DECLARATION"`) correspond to
 * `<fontcolor name="...">` entries in `FlatLafLight.xml`, `FlatLafDark.xml`, and
 * `NetBeans55.xml`. The resolved values therefore automatically reflect the user's
 * chosen theme without any hardcoded hex constants.
 *
 * Shared between:
 * - [KotlinSemanticHighlightsLayerFactory] — semantic editor highlighting
 * - `KtDocumentationRenderer` — hover/documentation popup signature coloring
 */
object KotlinColorResolver {

    /**
     * Returns the active-theme [AttributeSet] for [colorName], or `null` when
     * [FontColorSettings] is unavailable (e.g. outside a NetBeans runtime context) or
     * when [colorName] is not registered in the current theme.
     *
     * @param colorName the `FontAndColors.xml` category name (e.g. `"KOTLIN_FUNCTION_DECLARATION"`)
     */
    fun attributeSetFor(colorName: String): AttributeSet? =
        MimeLookup.getLookup("text/x-kotlin")
            .lookup(FontColorSettings::class.java)
            ?.getTokenFontColors(colorName)

    /**
     * Returns the foreground CSS hex color string (e.g. `"#00627a"`) for [colorName]
     * from the active user theme.
     *
     * Returns `null` when:
     * - [FontColorSettings] is not available (unit-test environment without NetBeans runtime)
     * - [colorName] is not present in the current theme file
     * - the theme entry has no explicit foreground override (the token inherits the editor default)
     *
     * @param colorName the `FontAndColors.xml` category name
     * @return a CSS hex string starting with `#`, or `null`
     */
    fun foregroundHex(colorName: String): String? {
        val attrs = attributeSetFor(colorName) ?: return null
        val color = StyleConstants.getForeground(attrs) ?: return null
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }
}
