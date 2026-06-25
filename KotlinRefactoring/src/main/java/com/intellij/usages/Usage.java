package com.intellij.usages;
public interface Usage {
    UsagePresentation getPresentation();
    boolean isValid();
    boolean isReadOnly();
    com.intellij.openapi.vfs.VirtualFile[] getFiles();
    void selectInEditor();
    void highlightInEditor();
    void navigate(boolean requestFocus);
    boolean canNavigate();
    boolean canNavigateToSource();
}
