package com.intellij.usages;
import com.intellij.usageView.UsageViewDescriptor;
public interface UsageView {
    void addPerformOperationAction(Runnable action, String commandName, String description, String shortDescription);
    void addPerformOperationAction(Runnable action, String commandName, String description, String shortDescription, boolean checkReadOnlyStatus);
    void setRerunAction(Runnable runnable);
    void doReRun();
    boolean isSearchInProgress();
    void setAdditionalComponent(java.awt.Component component);
}
