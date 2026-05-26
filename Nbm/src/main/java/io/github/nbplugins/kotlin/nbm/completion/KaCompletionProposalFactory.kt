/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.completion

import org.openide.filesystems.FileObject

/**
 * Converts [KaCompletionItem]s to NetBeans [KaCompletionProposal]s.
 *
 * All symbol-level decisions (kind, icon, signature) are already made by
 * [KaCompletionProvider] inside an `analyze {}` block; this factory is a thin mapper.
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs
 * other than [org.netbeans.modules.csl.api].
 */
object KaCompletionProposalFactory {

    /**
     * Converts [item] to a [KaCompletionProposal].
     *
     * @param item         the pre-rendered completion item from [KaCompletionProvider]
     * @param anchorOffset document offset where the identifier starts
     * @param prefix       already-typed prefix; removed on proposal insertion
     * @param fileObject   source file where completion was triggered; forwarded to
     *                     [KaCompletionProposal.getElement] to enable hover documentation
     * @return a [KaCompletionProposal]
     */
    fun toProposal(
        item: KaCompletionItem,
        anchorOffset: Int,
        prefix: String,
        fileObject: FileObject? = null,
    ): KaCompletionProposal =
        KaCompletionProposal(item.name, item.kind, item.icon, anchorOffset, prefix,
                             item.isFunctionLike, item.signature, item.sortPriority, fileObject)
}
