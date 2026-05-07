package com.intellij.psi.codeStyle;

import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.lang.Language;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.options.Configurable;

public abstract class LanguageCodeStyleSettingsProvider extends CodeStyleSettingsProvider {
    public static final ExtensionPointName<LanguageCodeStyleSettingsProvider> EP_NAME =
            new ExtensionPointName<>("com.intellij.langCodeStyleSettingsProvider");

    public enum SettingsType {
        BLANK_LINES_SETTINGS, SPACING_SETTINGS, WRAPPING_AND_BRACES_SETTINGS, INDENT_SETTINGS, COMMENTER_SETTINGS, LANGUAGE_SPECIFIC
    }

    @Override
    public Configurable createSettingsPage(CodeStyleSettings settings, CodeStyleSettings originalSettings) {
        throw new UnsupportedOperationException();
    }

    public abstract String getCodeSample(SettingsType settingsType);

    public abstract Language getLanguage();

    public void customizeSettings(CodeStyleSettingsCustomizable consumer, SettingsType settingsType) {}

    public String getLanguageName() { return null; }

    public IndentOptionsEditor getIndentOptionsEditor() { return null; }

    public CommonCodeStyleSettings getDefaultCommonSettings() { return null; }
}
