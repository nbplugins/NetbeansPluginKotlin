/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.navigation

import org.netbeans.api.project.Project
import org.openide.windows.TopComponent
import org.openide.windows.WindowManager
import java.awt.BorderLayout

/**
 * A dockable NetBeans window that hosts the [KotlinTypeHierarchyPanel].
 *
 * The window has `PERSISTENCE_NEVER` so it is not serialized between IDE sessions and is only
 * ever opened programmatically via [openFor]. A single instance per IDE session is reused
 * (looked up by [PREFERRED_ID] via [WindowManager]).
 *
 * No layer.xml entry is needed: the window opens only from [KotlinInspectHierarchyAction] and
 * is not restored on IDE startup.
 *
 * This class belongs to the **view** layer.
 */
class KotlinTypeHierarchyTopComponent private constructor() : TopComponent() {

    internal val panel = KotlinTypeHierarchyPanel()

    init {
        name = "Type Hierarchy"
        toolTipText = "Kotlin Type Hierarchy"
        layout = BorderLayout()
        add(panel, BorderLayout.CENTER)
    }

    /** Returns [PREFERRED_ID] so [WindowManager] can locate a previously opened instance. */
    override fun preferredID(): String = PREFERRED_ID

    /** Never persisted — the window is opened fresh from the action each session. */
    override fun getPersistenceType(): Int = PERSISTENCE_NEVER

    companion object {
        /** Stable identifier used by [WindowManager.findTopComponent]. */
        const val PREFERRED_ID = "KotlinTypeHierarchyTopComponent"

        /**
         * Opens (or re-activates) the hierarchy window and loads the hierarchy for [rootNode].
         *
         * If a window with [PREFERRED_ID] already exists it is reused; otherwise a new instance
         * is created. The [panel] is loaded with [rootNode] and the default [HierarchyMode.SUBTYPES]
         * view.
         *
         * Must be called from the EDT.
         *
         * @param rootNode The focal class to show at the tree root.
         * @param project The project whose source files are scanned for subtypes.
         */
        fun openFor(rootNode: KotlinTypeHierarchyNode, project: Project) {
            val tc = WindowManager.getDefault().findTopComponent(PREFERRED_ID)
                as? KotlinTypeHierarchyTopComponent
                ?: KotlinTypeHierarchyTopComponent()
            tc.panel.setRoot(rootNode, project)
            if (!tc.isOpened) tc.open()
            tc.requestActive()
        }
    }
}
