package org.jetbrains.kotlin.idea.base.resources;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** Provides formatter message strings from KotlinBundle.properties. */
public final class KotlinBundle {
    private static volatile ResourceBundle bundle;

    private static ResourceBundle getBundle() {
        if (bundle == null) {
            synchronized (KotlinBundle.class) {
                if (bundle == null) {
                    try { bundle = ResourceBundle.getBundle("messages.KotlinBundle"); }
                    catch (MissingResourceException ignored) {}
                }
            }
        }
        return bundle;
    }

    public static String message(String key, Object... params) {
        ResourceBundle b = getBundle();
        if (b != null) {
            try { return b.getString(key); }
            catch (MissingResourceException ignored) {}
        }
        return key;
    }

    private KotlinBundle() {}
}
