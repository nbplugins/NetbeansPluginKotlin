package com.intellij.usages.impl;
import com.intellij.usages.Usage;
public class UnknownUsagesInUnloadedModules implements Usage {
    public UnknownUsagesInUnloadedModules(String description) {}
    @Override public com.intellij.usages.UsagePresentation getPresentation() { return null; }
    @Override public boolean isValid() { return true; }
    @Override public boolean isReadOnly() { return true; }
    @Override public com.intellij.openapi.vfs.VirtualFile[] getFiles() { return null; }
    @Override public void selectInEditor() {}
    @Override public void highlightInEditor() {}
    @Override public void navigate(boolean f) {}
    @Override public boolean canNavigate() { return false; }
    @Override public boolean canNavigateToSource() { return false; }
}
