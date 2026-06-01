/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.options

import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import org.netbeans.spi.options.OptionsPanelController
import org.openide.util.HelpCtx
import org.openide.util.Lookup

/**
 * Top-level options controller for Tools → Options → Kotlin.
 *
 * <p>Registered manually in {@code layer.xml} under
 * {@code OptionsDialog/Kotlin.instance} because annotation processing
 * is disabled ({@code -proc:none}) in this module.
 *
 * <p>Reads and writes settings via
 * {@link KotlinCodeStylePreferences#prefs()} so that the formatter and
 * this panel always use the same {@link java.util.prefs.Preferences} node.
 */
class KotlinOptionsPanelController : OptionsPanelController() {

    private val pcs = PropertyChangeSupport(this)
    private var panel: KotlinOptionsPanel? = null
    private var changed = false

    /**
     * Reloads the panel from the current preferences.
     */
    override fun update() {
        getPanel().load(KotlinCodeStylePreferences.prefs())
        changed = false
    }

    /**
     * Persists the panel state to preferences and notifies the formatter.
     */
    override fun applyChanges() {
        val prefs = KotlinCodeStylePreferences.prefs()
        getPanel().store(prefs)
        prefs.flush()
        changed = false
    }

    /**
     * Discards unsaved changes.
     */
    override fun cancel() {
        changed = false
    }

    /** Always valid — no required fields. */
    override fun isValid(): Boolean = true

    /** Returns {@code true} if the user has modified any control since the last apply/update. */
    override fun isChanged(): Boolean = changed

    /**
     * Returns the panel component, creating it lazily on first call.
     *
     * @param masterLookup the Options dialog lookup (unused)
     * @return the panel
     */
    override fun getComponent(masterLookup: Lookup): JComponent = getPanel()

    /** Returns the default help context. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    override fun addPropertyChangeListener(l: PropertyChangeListener) =
        pcs.addPropertyChangeListener(l)

    override fun removePropertyChangeListener(l: PropertyChangeListener) =
        pcs.removePropertyChangeListener(l)

    private fun getPanel(): KotlinOptionsPanel {
        if (panel == null) {
            panel = KotlinOptionsPanel {
                changed = true
                pcs.firePropertyChange(PROP_CHANGED, false, true)
            }
        }
        return panel!!
    }
}
