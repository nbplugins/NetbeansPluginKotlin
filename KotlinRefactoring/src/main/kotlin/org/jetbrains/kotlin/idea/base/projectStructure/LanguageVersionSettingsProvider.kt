package org.jetbrains.kotlin.idea.base.projectStructure

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl

/** Stub: returns default language version settings in standalone mode. */
val PsiElement.languageVersionSettings: LanguageVersionSettings
    get() = LanguageVersionSettingsImpl.DEFAULT

/** Stub: returns default language version settings in standalone mode. */
val Project.languageVersionSettings: LanguageVersionSettings
    get() = LanguageVersionSettingsImpl.DEFAULT

/** Stub: returns default language version settings in standalone mode. */
val Module.languageVersionSettings: LanguageVersionSettings
    get() = LanguageVersionSettingsImpl.DEFAULT
