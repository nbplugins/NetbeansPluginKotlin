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

import io.github.nbplugins.kotlin.nbm.completion.KtDocumentationRenderer
import io.github.nbplugins.kotlin.nbm.navigation.KaNavigationUtils
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.utils.ProjectUtils

/**
 * Returns the HTML documentation tooltip for a Ctrl+hover over [referenceExpression], or an
 * empty string if nothing can be shown. Delegates to [KtDocumentationRenderer.buildHtml] so
 * the Ctrl+hover popup is identical to the plain-hover popup.
 *
 * @param referenceExpression the PSI reference under the cursor, or null
 * @param doc the editor document
 * @param offset the character offset of the identifier
 */
fun getToolTip(referenceExpression: KtReferenceExpression?,
               doc: Document, offset: Int): String {
    val file = ProjectUtils.getFileObjectForDocument(doc) ?: return ""
    val project = ProjectUtils.getKotlinProjectForFileObject(file)
        ?: ProjectUtils.getValidProject()
        ?: return ""

    val k2Expression = referenceExpression?.let { ref ->
        (ref as? KtSimpleNameExpression) ?: ref.children.filterIsInstance<KtSimpleNameExpression>().firstOrNull()
    }
    val smartCast = if (k2Expression != null) {
        KaNavigationUtils.getSmartCastDescription(k2Expression, project, file) ?: ""
    } else ""

    val html = KtDocumentationRenderer.buildHtml(project, file, offset)
    if (html != null) {
        return if (smartCast.isNotEmpty()) {
            html.replace(
                "</body></html>",
                "<hr style='margin:4px 0'><div style='padding:4px'>$smartCast</div></body></html>"
            )
        } else html
    }

    return smartCast
}
