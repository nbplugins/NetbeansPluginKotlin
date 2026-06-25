package com.intellij.ide.scratch;
public abstract class ScratchFileService {
    public static ScratchFileService getInstance() { return null; }
    public abstract RootType getRootType(com.intellij.openapi.vfs.VirtualFile file);
}
