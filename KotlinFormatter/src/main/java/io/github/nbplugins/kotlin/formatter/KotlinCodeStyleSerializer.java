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
package io.github.nbplugins.kotlin.formatter;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings;
import org.jetbrains.kotlin.idea.formatter.KotlinCommonCodeStyleSettings;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes {@link KotlinCodeStyleSettings} and
 * {@link IndentOptions} to/from XML strings, using the same format as
 * IntelliJ IDEA's {@code .idea/codeStyles/Project.xml}.
 *
 * <p>This class lives in the KotlinFormatter module so that it can access
 * {@code org.jdom.*} (available as a provided compile dependency there) without
 * requiring the Nbm module to declare its own JDOM dependency.
 */
public final class KotlinCodeStyleSerializer {

    private static final String KOTLIN_ELEMENT_NAME = "JetCodeStyleSettings";
    private static final String INDENT_ELEMENT_NAME = "IndentOptions";
    private static final String COMMON_ELEMENT_NAME = "KotlinCommonCodeStyleSettings";

    /** {@link CommonCodeStyleSettings} boolean fields shown in the Spaces, Wrapping, and Code Generation panels. */
    private static final String[] COMMON_BOOL_FIELDS = {
        // Spaces — around operators
        "SPACE_AROUND_ASSIGNMENT_OPERATORS", "SPACE_AROUND_LOGICAL_OPERATORS",
        "SPACE_AROUND_EQUALITY_OPERATORS", "SPACE_AROUND_RELATIONAL_OPERATORS",
        "SPACE_AROUND_ADDITIVE_OPERATORS", "SPACE_AROUND_MULTIPLICATIVE_OPERATORS",
        "SPACE_AROUND_UNARY_OPERATOR",
        // Spaces — commas and before parentheses
        "SPACE_AFTER_COMMA", "SPACE_BEFORE_COMMA",
        "SPACE_BEFORE_IF_PARENTHESES", "SPACE_BEFORE_WHILE_PARENTHESES",
        "SPACE_BEFORE_FOR_PARENTHESES", "SPACE_BEFORE_CATCH_PARENTHESES",
        // Wrapping — keep when reformatting
        "KEEP_LINE_BREAKS", "KEEP_FIRST_COLUMN_COMMENT",
        // Wrapping — function declarations / call arguments
        "ALIGN_MULTILINE_PARAMETERS", "METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE", "METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE",
        "ALIGN_MULTILINE_PARAMETERS_IN_CALLS", "CALL_PARAMETERS_LPAREN_ON_NEXT_LINE", "CALL_PARAMETERS_RPAREN_ON_NEXT_LINE",
        "ALIGN_MULTILINE_METHOD_BRACKETS",
        "WRAP_FIRST_METHOD_IN_CALL_CHAIN",
        // Wrapping — braces and control flow
        "ELSE_ON_NEW_LINE", "WHILE_ON_NEW_LINE", "CATCH_ON_NEW_LINE", "FINALLY_ON_NEW_LINE",
        "ALIGN_MULTILINE_EXTENDS_LIST",
        "ALIGN_MULTILINE_BINARY_OPERATION",
        // Code Generation — comment code
        "LINE_COMMENT_AT_FIRST_COLUMN", "LINE_COMMENT_ADD_SPACE", "LINE_COMMENT_ADD_SPACE_ON_REFORMAT",
        "BLOCK_COMMENT_AT_FIRST_COLUMN", "BLOCK_COMMENT_ADD_SPACE",
    };

    /** {@link CommonCodeStyleSettings} int (wrap-mode) fields shown in the Wrapping panel. */
    private static final String[] COMMON_INT_FIELDS = {
        "RIGHT_MARGIN", "WRAP_ON_TYPING",
        "METHOD_PARAMETERS_WRAP", "CALL_PARAMETERS_WRAP",
        "METHOD_CALL_CHAIN_WRAP", "EXTENDS_LIST_WRAP",
        "ASSIGNMENT_WRAP", "ENUM_CONSTANTS_WRAP",
        "METHOD_ANNOTATION_WRAP", "CLASS_ANNOTATION_WRAP",
        "PARAMETER_ANNOTATION_WRAP", "FIELD_ANNOTATION_WRAP", "VARIABLE_ANNOTATION_WRAP",
    };

    private KotlinCodeStyleSerializer() {}

    /**
     * Serializes {@link KotlinCodeStyleSettings} from {@code settings} to an XML
     * string, recording only fields that differ from the IDEA defaults.
     *
     * @param settings the source code-style settings
     * @return XML string in IntelliJ {@code JetCodeStyleSettings} format
     * @throws Exception if JDOM serialization fails
     */
    public static String serializeKotlinSettings(CodeStyleSettings settings) throws Exception {
        KotlinCodeStyleSettings ks = settings.getCustomSettings(KotlinCodeStyleSettings.class);
        KotlinCodeStyleSettings defaults = new KotlinCodeStyleSettings(CodeStyleSettings.getDefaults());
        Element element = new Element(KOTLIN_ELEMENT_NAME);
        ks.writeExternal(element, defaults);
        StringWriter sw = new StringWriter();
        new XMLOutputter(Format.getCompactFormat()).output(element, sw);
        return sw.toString();
    }

    /**
     * Deserializes {@link KotlinCodeStyleSettings} from an XML string produced by
     * {@link #serializeKotlinSettings} into {@code settings}.
     *
     * @param xml      XML string in {@code JetCodeStyleSettings} format
     * @param settings the target code-style settings
     * @throws Exception if the XML is malformed or JDOM deserialization fails
     */
    public static void deserializeKotlinSettings(String xml, CodeStyleSettings settings) throws Exception {
        KotlinCodeStyleSettings ks = settings.getCustomSettings(KotlinCodeStyleSettings.class);
        Element element = JDOMUtil.load((CharSequence) xml);
        ks.readExternal(element);
    }

    /**
     * Serializes {@link IndentOptions} from {@code settings} to an XML string.
     *
     * @param settings the source code-style settings
     * @return XML string with {@code INDENT_SIZE}, {@code CONTINUATION_INDENT_SIZE},
     *         {@code TAB_SIZE}, {@code USE_TAB_CHARACTER}, {@code SMART_TABS},
     *         and {@code KEEP_INDENTS_ON_EMPTY_LINES} attributes
     * @throws Exception if JDOM serialization fails
     */
    public static String serializeIndentOptions(CodeStyleSettings settings) throws Exception {
        IndentOptions opts = settings.getIndentOptions();
        Element element = new Element(INDENT_ELEMENT_NAME);
        element.setAttribute("INDENT_SIZE", String.valueOf(opts.INDENT_SIZE));
        element.setAttribute("CONTINUATION_INDENT_SIZE", String.valueOf(opts.CONTINUATION_INDENT_SIZE));
        element.setAttribute("TAB_SIZE", String.valueOf(opts.TAB_SIZE));
        element.setAttribute("USE_TAB_CHARACTER", String.valueOf(opts.USE_TAB_CHARACTER));
        element.setAttribute("SMART_TABS", String.valueOf(opts.SMART_TABS));
        element.setAttribute("KEEP_INDENTS_ON_EMPTY_LINES", String.valueOf(opts.KEEP_INDENTS_ON_EMPTY_LINES));
        StringWriter sw = new StringWriter();
        new XMLOutputter(Format.getCompactFormat()).output(element, sw);
        return sw.toString();
    }

    /**
     * Deserializes {@link IndentOptions} from an XML string produced by
     * {@link #serializeIndentOptions} into {@code settings}.
     *
     * @param xml      XML string with indent option attributes
     * @param settings the target code-style settings
     * @throws Exception if the XML is malformed or JDOM deserialization fails
     */
    public static void deserializeIndentOptions(String xml, CodeStyleSettings settings) throws Exception {
        Element element = JDOMUtil.load((CharSequence) xml);
        IndentOptions opts = settings.getIndentOptions();
        String v;
        if ((v = element.getAttributeValue("INDENT_SIZE")) != null)
            opts.INDENT_SIZE = Integer.parseInt(v);
        if ((v = element.getAttributeValue("CONTINUATION_INDENT_SIZE")) != null)
            opts.CONTINUATION_INDENT_SIZE = Integer.parseInt(v);
        if ((v = element.getAttributeValue("TAB_SIZE")) != null)
            opts.TAB_SIZE = Integer.parseInt(v);
        if ((v = element.getAttributeValue("USE_TAB_CHARACTER")) != null)
            opts.USE_TAB_CHARACTER = Boolean.parseBoolean(v);
        if ((v = element.getAttributeValue("SMART_TABS")) != null)
            opts.SMART_TABS = Boolean.parseBoolean(v);
        if ((v = element.getAttributeValue("KEEP_INDENTS_ON_EMPTY_LINES")) != null)
            opts.KEEP_INDENTS_ON_EMPTY_LINES = Boolean.parseBoolean(v);
    }

    /**
     * Serializes the {@link CommonCodeStyleSettings} fields shown in the Spaces and Wrapping
     * panels to an XML string. Stores all field values (not just diff-from-defaults), because
     * the style-preset mechanism applies KOTLIN_OFFICIAL defaults on construction, making a
     * true diff unreliable without re-applying the preset on deserialize.
     *
     * @param cs the source Kotlin common code-style settings
     * @return XML string in {@code KotlinCommonCodeStyleSettings} format
     * @throws Exception if field access or JDOM serialization fails
     */
    public static String serializeCommonSettings(KotlinCommonCodeStyleSettings cs) throws Exception {
        Element element = new Element(COMMON_ELEMENT_NAME);
        if (cs.CODE_STYLE_DEFAULTS != null) {
            element.setAttribute("CODE_STYLE_DEFAULTS", cs.CODE_STYLE_DEFAULTS);
        }
        for (String name : COMMON_BOOL_FIELDS) {
            boolean val = CommonCodeStyleSettings.class.getField(name).getBoolean(cs);
            element.setAttribute(name, String.valueOf(val));
        }
        for (String name : COMMON_INT_FIELDS) {
            int val = CommonCodeStyleSettings.class.getField(name).getInt(cs);
            element.setAttribute(name, String.valueOf(val));
        }
        List<Integer> sm = cs.getSoftMargins();
        if (!sm.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sm.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(sm.get(i));
            }
            element.setAttribute("SOFT_MARGINS", sb.toString());
        }
        StringWriter sw = new StringWriter();
        new XMLOutputter(Format.getCompactFormat()).output(element, sw);
        return sw.toString();
    }

    /**
     * Deserializes a {@link KotlinCommonCodeStyleSettings} from an XML string produced by
     * {@link #serializeCommonSettings} into {@code cs}.
     *
     * @param xml XML string in {@code KotlinCommonCodeStyleSettings} format
     * @param cs  the target Kotlin common code-style settings
     * @throws Exception if the XML is malformed or field access fails
     */
    public static void deserializeCommonSettings(String xml, KotlinCommonCodeStyleSettings cs) throws Exception {
        Element element = JDOMUtil.load((CharSequence) xml);
        String csd = element.getAttributeValue("CODE_STYLE_DEFAULTS");
        if (csd != null) cs.CODE_STYLE_DEFAULTS = csd;
        for (String name : COMMON_BOOL_FIELDS) {
            String v = element.getAttributeValue(name);
            if (v != null) CommonCodeStyleSettings.class.getField(name).setBoolean(cs, Boolean.parseBoolean(v));
        }
        for (String name : COMMON_INT_FIELDS) {
            String v = element.getAttributeValue(name);
            if (v != null) CommonCodeStyleSettings.class.getField(name).setInt(cs, Integer.parseInt(v));
        }
        String smStr = element.getAttributeValue("SOFT_MARGINS");
        if (smStr != null && !smStr.isBlank()) {
            List<Integer> margins = new ArrayList<>();
            for (String part : smStr.split(",")) {
                try { margins.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignore) {}
            }
            cs.setSoftMargins(margins);
        }
    }
}
