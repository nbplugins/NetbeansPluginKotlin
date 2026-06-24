package com.intellij.usages;
import javax.swing.Icon;
public interface UsagePresentation {
    String getPlainText();
    Icon getIcon();
    String getTooltipText();
}
