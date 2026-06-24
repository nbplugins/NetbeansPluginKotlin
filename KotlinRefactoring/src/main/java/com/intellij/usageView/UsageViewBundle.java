package com.intellij.usageView;
public final class UsageViewBundle {
    public static String getReferencesString(int usages, int files) { return usages + " usages in " + files + " files"; }
    public static String getOccurencesString(int usages, int files) { return usages + " occurrences in " + files + " files"; }
    public static String message(String key, Object... params) { return key; }
}
