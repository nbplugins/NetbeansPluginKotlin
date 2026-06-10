package com.intellij.application.options;

import com.intellij.psi.codeStyle.CodeStyleConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettings;

/** Compile-time stub. */
public abstract class CodeStyleAbstractConfigurable implements CodeStyleConfigurable {
    protected final CodeStyleSettings currentSettings;

    protected CodeStyleAbstractConfigurable(CodeStyleSettings settings,
                                            CodeStyleSettings modelSettings,
                                            String displayName) {
        this.currentSettings = settings;
    }

    public abstract CodeStyleAbstractPanel createPanel(CodeStyleSettings settings);
    public String getHelpTopic() { return null; }
}
