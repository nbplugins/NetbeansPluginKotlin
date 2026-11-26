// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package icons

import javax.swing.Icon

/**
 * Placeholder for the Kotlin base-resources icon set referenced by the headless New Project
 * Wizard templates.
 *
 * The multi-platform-mobile icon is shown only by the IDEA project-template chooser, which the
 * NetBeans plugin does not use; the layer declares `org/jetbrains/kotlin/kt.png` instead. The
 * real class lives in a Kotlin plugin module that is not packaged into the plugin.
 */
object KotlinBaseResourcesIcons {

    /** Icon used by every placeholder template entry. */
    private object Empty : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) = Unit
        override fun getIconWidth(): Int = 0
        override fun getIconHeight(): Int = 0
    }

    /** Icons of the project templates offered by the IDEA New Project Wizard. */
    object Wizard {
        val MultiplatformMobile: Icon = Empty
    }
}
