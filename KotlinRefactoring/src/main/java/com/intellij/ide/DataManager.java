package com.intellij.ide;
import com.intellij.openapi.actionSystem.DataContext;
public abstract class DataManager {
    public static DataManager getInstance() { return null; }
    public abstract DataContext getDataContext();
    public abstract DataContext getDataContext(java.awt.Component component);
}
