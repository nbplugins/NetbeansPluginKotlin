// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea

import javax.swing.Icon

/**
 * Placeholder Kotlin icon set referenced by the headless New Project Wizard templates.
 *
 * The icons are rendered only by the IDEA project-template chooser, which the NetBeans plugin
 * does not use (the layer declares `org/jetbrains/kotlin/kt.png` instead). The real
 * `KotlinIcons` class pulls in IDE icon resources and neighbouring Kotlin plugin modules,
 * so the wizard carries this minimal API-compatible stand-in.
 */
object KotlinIcons {

    /** Icon used by every placeholder template entry. */
    private object Empty : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) = Unit
        override fun getIconWidth(): Int = 0
        override fun getIconHeight(): Int = 0
    }

    /** Icons of the project templates offered by the IDEA New Project Wizard. */
    object Wizard {
        val CONSOLE: Icon = Empty
        val JS: Icon = Empty
        val MULTIPLATFORM_LIBRARY: Icon = Empty
        val NATIVE: Icon = Empty
        val NODE_JS: Icon = Empty
        val REACT_JS: Icon = Empty
        val WEB: Icon = Empty
    }
}
