package com.intellij.usages;
import com.intellij.usageView.UsageInfo;
public class UsageInfo2UsageAdapter implements Usage {
    public UsageInfo2UsageAdapter(UsageInfo info) {}
    @Override public com.intellij.usages.UsagePresentation getPresentation() { return null; }
    @Override public boolean isValid() { return false; }
    @Override public boolean isReadOnly() { return false; }
    @Override public com.intellij.openapi.vfs.VirtualFile[] getFiles() { return new com.intellij.openapi.vfs.VirtualFile[0]; }
    @Override public void selectInEditor() {}
    @Override public void highlightInEditor() {}
    @Override public void navigate(boolean focus) {}
    @Override public boolean canNavigate() { return false; }
    @Override public boolean canNavigateToSource() { return false; }
}
