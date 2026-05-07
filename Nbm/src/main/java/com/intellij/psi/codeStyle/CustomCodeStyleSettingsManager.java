package com.intellij.psi.codeStyle;

import org.jdom.Element;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// Stub replacing 241-era version: createCustomSettings uses reflection fallback since
// CodeStyleSettingsService has no registered factories in our test/plugin environment.
class CustomCodeStyleSettingsManager {
    private final Map<String, CustomCodeStyleSettings> myCustomSettings = new HashMap<>();
    private final CodeStyleSettings myRootSettings;

    CustomCodeStyleSettingsManager(CodeStyleSettings settings) {
        myRootSettings = settings;
    }

    void initCustomSettings() {
        // No registered factories in our environment; custom settings are created on demand via reflection.
    }

    <T extends CustomCodeStyleSettings> T getCustomSettings(Class<T> settingsClass) {
        String name = settingsClass.getName();
        CustomCodeStyleSettings existing = myCustomSettings.get(name);
        if (existing != null) return settingsClass.cast(existing);
        T instance = createViaReflection(settingsClass);
        if (instance != null) {
            myCustomSettings.put(name, instance);
            return instance;
        }
        throw new RuntimeException("Unable to get or create settings of #" + settingsClass.getSimpleName() + " (" + name + ")");
    }

    <T extends CustomCodeStyleSettings> T getCustomSettingsIfCreated(Class<T> settingsClass) {
        CustomCodeStyleSettings existing = myCustomSettings.get(settingsClass.getName());
        return existing != null ? settingsClass.cast(existing) : null;
    }

    void registerCustomSettings(CodeStyleSettings settings, CustomCodeStyleSettingsFactory factory) {
        CustomCodeStyleSettings cs = factory.createCustomSettings(settings);
        if (cs != null) myCustomSettings.put(cs.getClass().getName(), cs);
    }

    public void unregisterCustomSettings(CustomCodeStyleSettingsFactory factory) {
        // no-op in stub
    }

    void copyFrom(CodeStyleSettings source) {
        CustomCodeStyleSettingsManager other = source.getCustomCodeStyleSettingsManager();
        for (Map.Entry<String, CustomCodeStyleSettings> entry : other.myCustomSettings.entrySet()) {
            myCustomSettings.computeIfAbsent(entry.getKey(), k -> {
                try {
                    return entry.getValue().getClass()
                            .getDeclaredConstructor(CodeStyleSettings.class)
                            .newInstance(myRootSettings);
                } catch (Exception e) {
                    return null;
                }
            });
            CustomCodeStyleSettings local = myCustomSettings.get(entry.getKey());
            if (local != null) local.copyWith(myRootSettings);
        }
    }

    void notifySettingsBeforeLoading() {}
    void notifySettingsLoaded() {}

    Collection<CustomCodeStyleSettings> getAllSettings() {
        return Collections.unmodifiableCollection(myCustomSettings.values());
    }

    void readExternal(Element element) {}
    void writeExternal(Element element, CodeStyleSettings settings) {}

    private <T extends CustomCodeStyleSettings> T createViaReflection(Class<T> cls) {
        try {
            return cls.getDeclaredConstructor(CodeStyleSettings.class).newInstance(myRootSettings);
        } catch (Exception e1) {
            try {
                return cls.getDeclaredConstructor(CodeStyleSettings.class, boolean.class).newInstance(myRootSettings, false);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
