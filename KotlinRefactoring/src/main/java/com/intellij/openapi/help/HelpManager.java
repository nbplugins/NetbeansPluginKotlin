package com.intellij.openapi.help;
public abstract class HelpManager {
    public static HelpManager getInstance() { return new HelpManager() { @Override public void invokeHelp(String id) {} }; }
    public abstract void invokeHelp(String id);
}
