package com.intellij.usages;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.vfs.VirtualFile;
public interface UsageTarget extends com.intellij.pom.Navigatable, ItemPresentation {
    void findUsages();
    boolean isValid();
    boolean isReadOnly();
    VirtualFile[] getFiles();
    void update();
}
