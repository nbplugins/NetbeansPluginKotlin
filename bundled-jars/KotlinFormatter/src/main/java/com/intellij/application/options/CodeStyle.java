// Stub for com.intellij.application.options.CodeStyle (introduced in IntelliJ 2018.3 / Platform SDK 183).
// The full class is part of the IntelliJ Platform SDK (openapi.jar / platform-api.jar), which is not
// available in Maven Central and not in our bundled JARs.
// Only getSettings(Project) is stubbed — the sole method used by KotlinCodeStyleSettings.getInstance().
// Remove this file in A4.4 when the JetBrains Maven repo
// (packages.jetbrains.team/maven/p/ij/intellij-dependencies) is added as compileOnly source.
package com.intellij.application.options;

import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;

public class CodeStyle {
    public static CodeStyleSettings getSettings(Project project) {
        return CodeStyleSettingsManager.getSettings(project);
    }
}
