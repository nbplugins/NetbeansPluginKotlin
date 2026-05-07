package com.intellij.openapi.options;

import javax.swing.JComponent;

public interface Configurable {
    String getDisplayName();
    JComponent createComponent();
    boolean isModified();
    void apply() throws Exception;
    void reset();
}
