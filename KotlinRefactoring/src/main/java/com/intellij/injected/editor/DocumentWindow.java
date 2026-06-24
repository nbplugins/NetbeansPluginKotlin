package com.intellij.injected.editor;
import com.intellij.openapi.editor.Document;
public interface DocumentWindow extends Document {
    int injectedToHost(int injectedOffset);
    int hostToInjected(int hostOffset);
}
