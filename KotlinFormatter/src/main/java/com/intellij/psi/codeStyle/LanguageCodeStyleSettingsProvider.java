package com.intellij.psi.codeStyle;

import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.codeStyle.properties.CodeStyleFieldAccessor;
import com.intellij.application.options.codeStyle.properties.CodeStylePropertyAccessor;
import com.intellij.lang.Language;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/** Compile-time stub for KotlinFormatter — replaced by Nbm stub at runtime. */
public abstract class LanguageCodeStyleSettingsProvider {

    public enum SettingsType {
        BLANK_LINES_SETTINGS, SPACING_SETTINGS, WRAPPING_AND_BRACES_SETTINGS,
        INDENT_SETTINGS, COMMENTER_SETTINGS, LANGUAGE_SPECIFIC
    }

    public abstract Language getLanguage();
    public abstract String getCodeSample(SettingsType settingsType);

    public void customizeSettings(CodeStyleSettingsCustomizable consumer, SettingsType settingsType) {}
    public String getConfigurableDisplayName() { return null; }
    public CodeStyleConfigurable createConfigurable(CodeStyleSettings settings, CodeStyleSettings modelSettings) { return null; }
    public IndentOptionsEditor getIndentOptionsEditor() { return null; }
    public CommonCodeStyleSettings getDefaultCommonSettings() { return null; }
    public CustomCodeStyleSettings createCustomSettings(CodeStyleSettings settings) { return null; }
    public CodeStyleFieldAccessor<?, ?> getAccessor(Object obj, Field field) { return null; }
    public List<CodeStylePropertyAccessor<?>> getAdditionalAccessors(Object obj) { return Collections.emptyList(); }
    public boolean usesCommonKeepLineBreaks() { return false; }
}
