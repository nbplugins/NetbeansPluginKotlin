package com.intellij.psi.codeStyle;

import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.lang.Language;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.options.Configurable;

/**
 * Runtime stub — the Nbm classloader loads this in preference to any version
 * compiled into KotlinFormatter.jar.  KotlinLanguageCodeStyleSettingsProvider
 * (from KotlinFormatter) extends this class and overrides customizeSettings().
 */
public abstract class LanguageCodeStyleSettingsProvider extends CodeStyleSettingsProvider {

    public static final ExtensionPointName<LanguageCodeStyleSettingsProvider> EP_NAME =
            new ExtensionPointName<>("com.intellij.langCodeStyleSettingsProvider");

    public enum SettingsType {
        BLANK_LINES_SETTINGS, SPACING_SETTINGS, WRAPPING_AND_BRACES_SETTINGS,
        INDENT_SETTINGS, COMMENTER_SETTINGS, LANGUAGE_SPECIFIC
    }

    @Override
    public Configurable createSettingsPage(CodeStyleSettings settings, CodeStyleSettings originalSettings) {
        throw new UnsupportedOperationException();
    }

    public abstract String getCodeSample(SettingsType settingsType);

    public abstract Language getLanguage();

    public void customizeSettings(CodeStyleSettingsCustomizable consumer, SettingsType settingsType) {}

    public String getLanguageName() { return null; }

    public String getConfigurableDisplayName() { return null; }

    public IndentOptionsEditor getIndentOptionsEditor() { return null; }

    public CommonCodeStyleSettings getDefaultCommonSettings() { return null; }

    public CustomCodeStyleSettings createCustomSettings(CodeStyleSettings settings) { return null; }

    /** Called by KotlinLanguageCodeStyleSettingsProvider.getAccessor() via super. */
    public Object getAccessor(Object codeStyleObject, java.lang.reflect.Field field) { return null; }

    /** Called by KotlinLanguageCodeStyleSettingsProvider.getAdditionalAccessors() via super. */
    public java.util.List<?> getAdditionalAccessors(Object codeStyleObject) { return java.util.Collections.emptyList(); }

    public boolean usesCommonKeepLineBreaks() { return false; }
}
