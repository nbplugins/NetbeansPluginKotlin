package com.intellij.openapi.ui;
public final class MessageDialogBuilder {
    public static YesNoCancel yesNoCancel(String title, String message) { return new YesNoCancel(); }
    public static class YesNoCancel {
        public YesNoCancel project(com.intellij.openapi.project.Project p) { return this; }
        public int show() { return 0; }
    }
}
