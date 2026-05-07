package com.intellij;

// Stub replacing core:241.194 DynamicBundle — no dynamic plugin bundle support needed in our environment.
public class DynamicBundle extends AbstractBundle {
    public static final DynamicBundle INSTANCE = new DynamicBundle("messages.CoreBundle");

    public DynamicBundle(Class<?> bundleClass, String pathToBundle) {
        super(pathToBundle);
    }

    protected DynamicBundle(String pathToBundle) {
        super(pathToBundle);
    }
}
