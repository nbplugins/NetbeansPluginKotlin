package com.intellij.psi.codeStyle;

/** Compile-time stub for KotlinFormatter — replaced by Nbm stub at runtime. */
public interface CodeStyleSettingsCustomizable {
    int[] WRAP_VALUES = {0, 1, 5};
    String[] WRAP_OPTIONS = {"Do not wrap", "Wrap if long", "Wrap always"};
    int[] WRAP_VALUES_FOR_SINGLETON = {0, 1};

    default void showStandardOptions(String... optionNames) {}
    default void showCustomOption(Class<?> settingsClass, String fieldName, String title,
                                  String groupName, Object... options) {}
    default void renameStandardOption(String fieldName, String newTitle) {}
}
