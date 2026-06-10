package com.intellij.psi.codeStyle;

/**
 * Interface implemented by our KotlinStyleOptionsCollector to receive option
 * definitions from KotlinLanguageCodeStyleSettingsProvider.customizeSettings().
 */
public interface CodeStyleSettingsCustomizable {
    /** Stored int values for 3-way wrap combobox: don't wrap / wrap if long / wrap always. */
    int[] WRAP_VALUES = {0, 1, 5};
    /** Display strings for WRAP_VALUES. */
    String[] WRAP_OPTIONS = {"Do not wrap", "Wrap if long", "Wrap always"};
    /** Stored int values for 2-way wrap combobox: don't wrap / wrap if long. */
    int[] WRAP_VALUES_FOR_SINGLETON = {0, 1};

    /**
     * Called for each standard CommonCodeStyleSettings field that should be shown.
     *
     * @param optionNames public field names on CommonCodeStyleSettings
     */
    default void showStandardOptions(String... optionNames) {}

    /**
     * Called for each custom KotlinCodeStyleSettings field that should be shown.
     *
     * @param settingsClass the settings class that owns the field (KotlinCodeStyleSettings)
     * @param fieldName     the field name
     * @param title         the display label
     * @param groupName     the group header (null → no group)
     * @param options       optional: [String[] displayValues, int[] storedValues] for wrap items
     */
    default void showCustomOption(Class<?> settingsClass, String fieldName, String title,
                                  String groupName, Object... options) {}

    /**
     * Renames a standard option or group identified by key.
     *
     * @param fieldOrGroupName field name or group identifier constant
     * @param newTitle         new display label
     */
    default void renameStandardOption(String fieldOrGroupName, String newTitle) {}
}
