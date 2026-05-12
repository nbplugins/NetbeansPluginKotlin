package com.intellij.openapi.util.registry;

import java.io.IOException;
import java.io.InputStream;
import java.util.MissingResourceException;
import java.util.Properties;

/**
 * Stub for kotlin-compiler 2.0.21 era Registry.
 * The real Registry requires XML-contributed keys loaded via extension points (IDE startup),
 * which don't run in standalone/test mode. Loads keys from misc/registry.properties on the
 * classpath instead, matching the same defaults the real implementation would use.
 */
public final class Registry {

    private static final Properties PROPS = new Properties();
    private static final Registry INSTANCE = new Registry();

    static {
        try (InputStream in = Registry.class.getClassLoader().getResourceAsStream("misc/registry.properties")) {
            if (in != null) PROPS.load(in);
        } catch (IOException ignored) {}
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
