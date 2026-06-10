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
package io.github.nbplugins.kotlin.nbm.options.formatter

import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * A tab content panel that embeds a settings panel on the left and a live
 * [KotlinFormattingPreviewPane] on the right (60 / 40 split).
 *
 * @param settingsPanel  the panel with formatter controls
 * @param previewSource  Kotlin source snippet to display in the preview
 * @param collectSettings callback that fills a [Preferences] with the current
 *                        state of all panels so the preview can reformat on demand
 */
class KotlinFormatterTabWithPreview(
    val settingsPanel: JComponent,
    previewSource: String,
    collectSettings: (Preferences) -> Unit
) : JPanel(BorderLayout()) {

    val previewPane = KotlinFormattingPreviewPane(collectSettings)

    private val split = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT, settingsPanel, previewPane
    ).apply { resizeWeight = 0.60 }

    init {
        add(split, BorderLayout.CENTER)
        previewPane.setSource(previewSource)
    }

    override fun addNotify() {
        super.addNotify()
        split.setDividerLocation(0.60)
    }
}
