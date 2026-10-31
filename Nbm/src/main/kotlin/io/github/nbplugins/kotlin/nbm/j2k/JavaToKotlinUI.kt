/*
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
 */
package io.github.nbplugins.kotlin.nbm.j2k

import javax.swing.event.ChangeListener
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI
import org.openide.util.HelpCtx

/**
 * NetBeans UI adapter for **Refactor → Convert to Kotlin**.
 *
 * The conversion has no user-editable parameters, so the adapter returns no parameters panel:
 * [org.netbeans.modules.refactoring.spi.ui.UI.openRefactoringUI] opens the standard Refactoring
 * window directly, showing the converted file and a **Refactor** button. Pressing **Refactor**
 * runs [JavaToKotlinPlugin] under NetBeans' own modal "Refactoring..." progress dialog, which
 * closes when the conversion finishes.
 *
 * Returning `null` from [getPanel] together with `hasParameters() == false` keeps the parameters
 * dialog out of the flow. [JavaToKotlinPlugin] must also stay a plain
 * [org.netbeans.modules.refactoring.spi.RefactoringPlugin] (not a
 * [org.netbeans.modules.refactoring.spi.ProgressProvider]) for the same reason: NetBeans shows the
 * parameters dialog of a refactoring that has a progress-providing plugin even when it has no
 * parameters.
 *
 * This is the **view/controller** layer: it carries no conversion logic, only the labels and the
 * link back to [JavaToKotlinRefactoring].
 *
 * @param refactoring carrier holding the Java file to convert
 */
class JavaToKotlinUI(
    private val refactoring: JavaToKotlinRefactoring,
) : RefactoringUI {

    /** @return refactoring name shown as the dialog/window title. */
    override fun getName(): String = NAME

    /** @return description used as the window tooltip and the element tree root label. */
    override fun getDescription(): String = "Converts ${refactoring.javaFile.nameExt} to Kotlin."

    /** @return always `false`: the conversion changes code rather than collecting usages. */
    override fun isQuery(): Boolean = false

    /**
     * @param parent ignored, because there are no parameters to connect to
     * @return always `null`: the refactoring takes no parameters
     */
    override fun getPanel(parent: ChangeListener?): CustomRefactoringPanel? = null

    /** @return no problem, because there is nothing to transfer into the refactoring. */
    override fun setParameters(): Problem? = null

    /** @return no problem, because there are no parameters to check. */
    override fun checkParameters(): Problem? = null

    /** @return always `false`: the conversion is driven by the Refactoring window alone. */
    override fun hasParameters(): Boolean = false

    /** @return the carrier the refactoring framework passes to [JavaToKotlinPlugin]. */
    override fun getRefactoring(): AbstractRefactoring = refactoring

    /** @return no dedicated help page. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    companion object {
        /** Refactoring name, shared with the action label. */
        const val NAME = "Convert to Kotlin"
    }
}
