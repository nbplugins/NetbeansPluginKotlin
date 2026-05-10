package com.intellij;

// Stub: adds DynamicBundle(Class,String) constructor absent from kotlin-compiler:1.9.25's shaded copy.
public class DynamicBundle extends AbstractBundle {
    public static final DynamicBundle INSTANCE = new DynamicBundle("messages.CoreBundle");

    public DynamicBundle(Class<?> bundleClass, String pathToBundle) {
        super(pathToBundle);
    }

    protected DynamicBundle(String pathToBundle) {
        super(pathToBundle);
    }
}
