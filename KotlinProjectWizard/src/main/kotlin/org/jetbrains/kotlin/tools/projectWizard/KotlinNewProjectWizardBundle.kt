// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.tools.projectWizard

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

@NonNls
private const val BUNDLE = "messages.KotlinNewProjectWizardBundle"

/**
 * Standalone replacement for IDEA's [com.intellij.DynamicBundle]-based message bundle.
 *
 * The original implementation resolves messages through `LocalizationUtil`, which requires a
 * real IDEA application instance. The NetBeans plugin runs the wizard under a plain
 * `MockApplication`, so the bundle is read directly from the classpath with [ResourceBundle]
 * and formatted with [MessageFormat] — the same mechanics `DynamicBundle` uses for `{0}`
 * placeholders, but without any IDEA application dependency.
 */
object KotlinNewProjectWizardBundle {

    private val bundle: ResourceBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ResourceBundle.getBundle(BUNDLE, Locale.getDefault(), KotlinNewProjectWizardBundle::class.java.classLoader)
    }

    /**
     * Returns the message with the given [key], formatted with [params].
     *
     * @param key resource-bundle key from `messages/KotlinNewProjectWizardBundle.properties`
     * @param params values substituted into the `{0}`, `{1}`, ... placeholders
     * @return localized message, or the key itself if the bundle has no such entry
     */
    @JvmStatic
    @Nls
    fun message(@NonNls key: String, vararg params: Any): String {
        val pattern = try {
            bundle.getString(key)
        } catch (e: java.util.MissingResourceException) {
            // A missing key must not crash project generation; surfacing the key makes the gap obvious.
            return key
        }
        return if (params.isEmpty()) pattern else MessageFormat(pattern, Locale.getDefault()).format(params)
    }
}
