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
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings;

import java.io.StringWriter;

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
     *         {@code TAB_SIZE}, and {@code USE_TAB_CHARACTER} attributes
     * @throws Exception if JDOM serialization fails
     */
    public static String serializeIndentOptions(CodeStyleSettings settings) throws Exception {
        IndentOptions opts = settings.getIndentOptions();
        Element element = new Element(INDENT_ELEMENT_NAME);
        element.setAttribute("INDENT_SIZE", String.valueOf(opts.INDENT_SIZE));
        element.setAttribute("CONTINUATION_INDENT_SIZE", String.valueOf(opts.CONTINUATION_INDENT_SIZE));
        element.setAttribute("TAB_SIZE", String.valueOf(opts.TAB_SIZE));
        element.setAttribute("USE_TAB_CHARACTER", String.valueOf(opts.USE_TAB_CHARACTER));
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
    }
}
