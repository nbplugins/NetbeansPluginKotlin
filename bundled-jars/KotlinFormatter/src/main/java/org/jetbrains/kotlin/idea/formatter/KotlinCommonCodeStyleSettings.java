// Stub replacing KotlinCommonCodeStyleSettings.java from submodules/Kotlin.
// The original file calls getSoftMargins(), arrangementSettingsEqual(), XmlSerializer.serializeInto(),
// and IndentOptions.copyFrom() — all introduced in IntelliJ 2019.3 (Platform SDK 193), which is not
// available in Maven Central and not in our bundled JARs.
// The formatting runtime only needs CODE_STYLE_DEFAULTS and the CommonCodeStyleSettings fields;
// IDE persistence methods (readExternal, writeExternal, equals, clone) are stubs.
// Remove this file in A4.4 when the JetBrains Maven repo is added as a compileOnly source.
package org.jetbrains.kotlin.idea.formatter;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.idea.util.ReflectionUtil;
import org.jdom.Element;

public class KotlinCommonCodeStyleSettings extends CommonCodeStyleSettings {
    @ReflectionUtil.SkipInEquals
    public String CODE_STYLE_DEFAULTS = null;

    public KotlinCommonCodeStyleSettings() {
        super(KotlinLanguage.INSTANCE);
    }

    @Override
    public CommonCodeStyleSettings clone(CodeStyleSettings rootSettings) {
        KotlinCommonCodeStyleSettings copy = new KotlinCommonCodeStyleSettings();
        copy.CODE_STYLE_DEFAULTS = this.CODE_STYLE_DEFAULTS;
        return copy;
    }

    @Override
    public void readExternal(Element element) {
        try { super.readExternal(element); } catch (Exception ignored) {}
    }

    @Override
    public void writeExternal(Element element) {
        try { super.writeExternal(element); } catch (Exception ignored) {}
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof KotlinCommonCodeStyleSettings &&
               ReflectionUtil.comparePublicNonFinalFieldsWithSkip(this, obj);
    }
}
