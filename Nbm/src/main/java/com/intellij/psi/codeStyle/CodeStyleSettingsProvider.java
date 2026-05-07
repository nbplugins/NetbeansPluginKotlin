package com.intellij.psi.codeStyle;

import com.intellij.lang.Language;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.options.Configurable;

public abstract class CodeStyleSettingsProvider {
    public static final ExtensionPointName<CodeStyleSettingsProvider> EXTENSION_POINT_NAME =
            ExtensionPointName.create("com.intellij.codeStyleSettingsProvider");

    public CustomCodeStyleSettings createCustomSettings(CodeStyleSettings settings) { return null; }

    public abstract Configurable createSettingsPage(CodeStyleSettings settings, CodeStyleSettings originalSettings);

    public String getConfigurableDisplayName() { return null; }

    public boolean hasSettingsPage() { return true; }

    public Language getLanguage() { return null; }
}
