// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.ui

import com.intellij.openapi.project.Project

/** Stub for [Messages]: UI dialogs are not shown in standalone NetBeans mode. */
object Messages {
    const val YES = 0
    const val NO = 1
    const val CANCEL = 2

    /** Stub: always returns [YES] in standalone mode. */
    @JvmStatic
    fun showYesNoCancelDialog(
        project: Project?,
        message: String,
        title: String,
        yesText: String,
        noText: String,
        cancelText: String,
        icon: Any? = null,
    ): Int = YES

    /** Stub: always returns [YES] in standalone mode. */
    @JvmStatic
    fun showYesNoCancelDialog(
        project: Project?,
        message: String,
        title: String,
        icon: Any? = null,
    ): Int = YES

    /**
     * Stub: always returns [YES] in standalone mode.
     *
     * NetBeans invokes the refactoring only after the user accepts its own dialog; IDEA's no-
     * inheritor warning therefore must not open a second UI surface.
     */
    @JvmStatic
    fun showYesNoDialog(message: String, title: String, icon: Any? = null): Int = YES

    /** Stub: always returns `true` in standalone mode. */
    @JvmStatic
    fun showYesNoDialog(project: Project?, message: String, title: String, icon: Any? = null): Boolean = true

    /** @return a placeholder IDEA warning icon; NetBeans never renders it. */
    @JvmStatic
    fun getWarningIcon(): Any? = null
}

/** Alias so callers can `import com.intellij.openapi.ui.Messages.showYesNoCancelDialog`. */
fun showYesNoCancelDialog(
    project: Project?,
    message: String,
    title: String,
    yesText: String = "Yes",
    noText: String = "No",
    cancelText: String = "Cancel",
    icon: Any? = null,
): Int = Messages.YES
