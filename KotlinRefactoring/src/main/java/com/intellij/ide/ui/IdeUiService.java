package com.intellij.ide.ui;
public abstract class IdeUiService {
    public static IdeUiService getInstance() { return null; }
    public abstract void revealFile(com.intellij.openapi.vfs.VirtualFile file);
}
