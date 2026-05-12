package com.intellij.openapi.util.registry;

public class RegistryValue {
    private final String key;
    private String value;

    public RegistryValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String asString() { return value; }
    public boolean asBoolean() { return Boolean.parseBoolean(value); }
    public int asInteger() { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return 0; } }
    public String getSelectedOption() { return value; }
    public boolean isChangedFromDefault() { return false; }
    public void setValue(boolean v) { this.value = Boolean.toString(v); }
    public void setValue(String v) { this.value = v; }
    public void setValue(boolean v, com.intellij.openapi.Disposable d) { setValue(v); }

    // Used by RegistryManager implementations
    public int get(int defaultValue) { return asInteger(); }
    public boolean get(boolean defaultValue) { return asBoolean(); }

    @Override public String toString() { return key + "=" + value; }
}
