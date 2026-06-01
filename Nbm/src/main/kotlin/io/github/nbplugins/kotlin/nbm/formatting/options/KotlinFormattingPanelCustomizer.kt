// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.formatting.options

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import org.netbeans.modules.options.editor.spi.PreferencesCustomizer
import org.openide.util.HelpCtx
import java.util.prefs.Preferences
import javax.swing.JComponent

/**
 * NetBeans [PreferencesCustomizer] adapter that wraps an IntelliJ
 * [CodeStyleAbstractPanel].
 *
 * <p>On construction the panel is reset to the current settings loaded from
 * the supplied [Preferences] node. Whenever the user changes a value, the
 * panel's apply/isModified methods are driven by the NetBeans framework;
 * the bridge saves changed values back to [Preferences] and also updates
 * the in-memory [CodeStyleSettings] singleton so the formatter immediately
 * picks up the new values.
 *
 * @param id      the unique panel identifier (used by NetBeans to match tabs)
 * @param prefs   the backing preferences node
 * @param panel   the wrapped IntelliJ panel
 */
class KotlinFormattingPanelCustomizer(
    private val id: String,
    private val prefs: Preferences,
    private val panel: CodeStyleAbstractPanel
) : PreferencesCustomizer {

    private val workingCopy: CodeStyleSettings = KotlinFormatterUtils.getSettings().clone()

    init {
        // Load current preferences into the working copy, then reset the panel from it.
        KotlinCodeStylePreferences.load(prefs, workingCopy)
        panel.reset(workingCopy)
    }

    override fun getId(): String = id

    override fun getDisplayName(): String = panel.getTabTitle()

    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    override fun getComponent(): JComponent = panel.getPanel()

    /**
     * Called by the NetBeans framework when the user confirms the dialog.
     * Applies the panel state to the working copy, then persists to [Preferences]
     * and to the global formatter settings singleton.
     */
    fun apply() {
        panel.apply(workingCopy)
        KotlinCodeStylePreferences.save(workingCopy, prefs)
        KotlinCodeStylePreferences.load(prefs, KotlinFormatterUtils.getSettings())
        prefs.flush()
    }

    /**
     * Returns {@code true} if the panel state differs from the current working copy.
     *
     * @return whether there are uncommitted changes
     */
    fun isModified(): Boolean = panel.isModified(workingCopy)

    /**
     * Resets the panel to the last saved state by reloading from [Preferences].
     */
    fun reset() {
        KotlinCodeStylePreferences.load(prefs, workingCopy)
        panel.reset(workingCopy)
    }
}
