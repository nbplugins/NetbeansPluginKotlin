package com.intellij.openapi.util.registry;

import java.io.IOException;
import java.io.InputStream;
import java.util.MissingResourceException;
import java.util.Properties;

/**
 * Stub for Registry compatible with both the 2.0.21-era API and the 2.3.21 Kotlin API.
 * The 2.3.21 analysis-api accesses Registry via a static Companion field (Kotlin companion object);
 * this Java stub exposes a compatible Companion inner class with the same method signatures.
 */
public final class Registry {

    public static final Companion Companion = new Companion();

    private static final Properties PROPS = new Properties();
    private static final Registry INSTANCE = new Registry();

    static {
        try (InputStream in = Registry.class.getClassLoader().getResourceAsStream("misc/registry.properties")) {
            if (in != null) PROPS.load(in);
        } catch (IOException ignored) {}
    }

    /** Mirrors the Kotlin companion object that analysis-api:2.3.21 accesses via Registry.Companion. */
    public static final class Companion {
        private Companion() {}

        public RegistryValue get(String key) { return Registry.get(key); }
        public boolean is(String key) throws MissingResourceException { return Registry.is(key); }
        public boolean is(String key, boolean defaultValue) { return Registry.is(key, defaultValue); }
        public int intValue(String key) throws MissingResourceException { return Registry.intValue(key); }
        public int intValue(String key, int defaultValue) { return Registry.intValue(key, defaultValue); }
        public Registry getInstance() { return Registry.getInstance(); }
    }

    public static RegistryValue get(String key) {
        return new RegistryValue(key, PROPS.getProperty(key, "false"));
    }

    public static boolean is(String key) throws MissingResourceException {
        return Boolean.parseBoolean(PROPS.getProperty(key, "false"));
    }

    public static boolean is(String key, boolean defaultValue) {
        String v = PROPS.getProperty(key);
        return v != null ? Boolean.parseBoolean(v) : defaultValue;
    }

    public static int intValue(String key) throws MissingResourceException {
        String v = PROPS.getProperty(key, "0");
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    public static int intValue(String key, int defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    public String getBundleValueOrNull(String key) {
        return PROPS.getProperty(key);
    }

    public String getBundleValue(String key) throws MissingResourceException {
        return PROPS.getProperty(key, "false");
    }

    public static Registry getInstance() { return INSTANCE; }

    public boolean isLoaded() { return true; }
}
