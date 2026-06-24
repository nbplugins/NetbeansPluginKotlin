package com.intellij.openapi.ui;
import javax.swing.*;
public abstract class DialogWrapper {
    public static final int OK_EXIT_CODE = 0;
    public static final int CANCEL_EXIT_CODE = 1;
    protected DialogWrapper(com.intellij.openapi.project.Project project, boolean canBeParent) {}
    protected DialogWrapper(boolean canBeParent) {}
    protected abstract JComponent createCenterPanel();
    protected void init() {}
    public void show() {}
    public boolean showAndGet() { return false; }
    public int getExitCode() { return CANCEL_EXIT_CODE; }
    protected JButton createJButtonForAction(Action action) { return new JButton(); }
    protected Action[] createActions() { return new Action[0]; }
    public void dispose() {}
    public JComponent getPreferredFocusedComponent() { return null; }
    protected String getDimensionServiceKey() { return null; }
}
