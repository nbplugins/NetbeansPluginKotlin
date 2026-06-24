package com.intellij.usages;
public interface ConfigurableUsageTarget extends UsageTarget {
    void showSettings();
    javax.swing.KeyStroke getShortcut();
    String getLongDescriptiveName();
}
