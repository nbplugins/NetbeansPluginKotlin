// Stub replacing KtCodeStyleSettings.kt from submodules/Kotlin.
// The original file imports com.intellij.application.options.CodeStyle (IntelliJ 2019+ API)
// which is absent from our bundled JARs. Only the two extension properties used by
// KotlinCommonBlock.kt and kotlinSpacingRules.kt are included here.
// Remove this file in A4.4 / B2 when bundled JARs are replaced with IntelliJCommunity.
package org.jetbrains.kotlin.idea.formatter

import com.intellij.psi.codeStyle.CodeStyleSettings
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings

val CodeStyleSettings.kotlinCommonSettings: KotlinCommonCodeStyleSettings
    get() = getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
        ?: KotlinCommonCodeStyleSettings()

val CodeStyleSettings.kotlinCustomSettings: KotlinCodeStyleSettings
    get() = getCustomSettings(KotlinCodeStyleSettings::class.java)
