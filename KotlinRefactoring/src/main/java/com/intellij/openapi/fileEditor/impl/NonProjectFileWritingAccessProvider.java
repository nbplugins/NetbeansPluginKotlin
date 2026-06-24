package com.intellij.openapi.fileEditor.impl;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Collection;
public abstract class NonProjectFileWritingAccessProvider {
    public static void allowWriting(Collection<? extends VirtualFile> files) {}
}
