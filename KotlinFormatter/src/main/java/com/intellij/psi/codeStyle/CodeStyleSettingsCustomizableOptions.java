package com.intellij.psi.codeStyle;

/** Compile-time stub for KotlinFormatter — provides group-name constants used by customizeSettings(). */
public final class CodeStyleSettingsCustomizableOptions {
    public final String SPACES_AROUND_OPERATORS = "Around operators";
    public final String SPACES_BEFORE_PARENTHESES = "Before parentheses";
    public final String SPACES_BEFORE_LEFT_BRACE = "Before left brace";
    public final String SPACES_WITHIN = "Within";
    public final String SPACES_OTHER = "Other";
    public final String BLANK_LINES_KEEP = "Keep blank lines";
    public final String BLANK_LINES = "Minimum blank lines";
    public final String WRAPPING_KEEP = "Keep when reformatting";
    public final String WRAPPING_BRACES = "Braces";
    public final String WRAPPING_METHOD_PARAMETERS = "Method declaration parameters";
    public final String WRAPPING_METHOD_PARENTHESES = "Method parentheses";
    public final String WRAPPING_METHOD_ARGUMENTS_WRAPPING = "Method call arguments";
    public final String WRAPPING_CALL_CHAIN = "Chained method calls";
    public final String WRAPPING_IF_STATEMENT = "'if()' statement";
    public final String WRAPPING_FOR_STATEMENT = "'for()' statement";
    public final String WRAPPING_WHILE_STATEMENT = "'while()' statement";
    public final String WRAPPING_SWITCH_STATEMENT = "'switch' statement";
    public final String WRAPPING_TRY_STATEMENT = "'try' statement";
    public final String WRAPPING_BINARY_OPERATION = "Binary operations";
    public final String WRAPPING_EXTENDS_LIST = "Extends/implements list";
    public final String WRAPPING_TERNARY_OPERATION = "Ternary operation";
    public final String WRAPPING_ASSIGNMENT = "Assignment statement";
    public final String[] WRAP_OPTIONS = {"Do not wrap", "Wrap if long", "Chop down if long", "Wrap always"};
    public final String[] WRAP_OPTIONS_FOR_SINGLETON = {"Do not wrap", "Wrap if long"};
    public final String[] BRACE_OPTIONS = {};

    private static final CodeStyleSettingsCustomizableOptions INSTANCE = new CodeStyleSettingsCustomizableOptions();

    private CodeStyleSettingsCustomizableOptions() {}

    public static CodeStyleSettingsCustomizableOptions getInstance() { return INSTANCE; }
}
