package org.jetbrains.kotlin.idea.formatter;

import com.intellij.application.options.codeStyle.properties.CodeStyleChoiceList;
import com.intellij.application.options.codeStyle.properties.CodeStylePropertyAccessor;
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings;
import java.util.List;

/** Compile-time stub for excluded KotlinCodeStylePropertyAccessor.kt. */
public class KotlinCodeStylePropertyAccessor extends CodeStylePropertyAccessor<String>
        implements CodeStyleChoiceList {
    public KotlinCodeStylePropertyAccessor(KotlinCodeStyleSettings settings) {}
    @Override public List<String> getChoices() { return List.of(); }
}
