/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.navigation.netbeans

import io.github.nbplugins.kotlin.nbm.navigation.KaNavigationUtils
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.utils.ProjectUtils

/**
 * Returns the tooltip string for a hover over [referenceExpression], or an empty string if nothing
 * meaningful can be shown. Uses the K2 Analysis API exclusively.
 *
 * @param referenceExpression the PSI reference under the cursor, or null
 * @param doc the editor document
 * @param offset the caret offset (unused; kept for interface compatibility)
 */
fun getToolTip(referenceExpression: KtReferenceExpression?,
               doc: Document, offset: Int): String {
    val file = referenceExpression?.let { ProjectUtils.getFileObjectForDocument(doc) }
    val project = file?.let {
        ProjectUtils.getKotlinProjectForFileObject(it) ?: ProjectUtils.getValidProject()
    }

    val k2Expression = referenceExpression?.let { ref ->
        (ref as? KtSimpleNameExpression) ?: ref.children.filterIsInstance<KtSimpleNameExpression>().firstOrNull()
    }
    val smartCast = if (k2Expression != null && file != null && project != null) {
        KaNavigationUtils.getSmartCastDescription(k2Expression, project, file) ?: ""
    } else ""

    referenceExpression ?: return smartCast
    if (file == null || project == null) return smartCast

    val k2Tooltip = KaNavigationUtils.renderDeclarationTooltip(referenceExpression, project, file)
    if (k2Tooltip != null) {
        return "$k2Tooltip${if (smartCast.isNotEmpty()) "\n\n$smartCast" else ""}"
    }

    return smartCast
}
