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
package io.github.nbplugins.kotlin.nbm.structurescanner

import org.netbeans.api.editor.fold.FoldTemplate
import org.netbeans.api.editor.fold.FoldType
import org.netbeans.spi.editor.fold.FoldTypeProvider

/**
 * Supplies the [FoldType]s used for Kotlin code folding to the NetBeans editor.
 *
 * The CSL fold pipeline ([org.netbeans.modules.csl.editor.fold.GsfFoldManager]) calls
 * `KotlinStructureScanner.folds()`, which (via `KotlinFoldingVisitor`) returns fold
 * ranges keyed by the strings `imports`, `comments` and `codeblocks`. `GsfFoldManager`
 * first tries to match those keys against the `FoldType.code()` values registered for
 * the MIME type and only then falls back to its built-in legacy types. By registering
 * this provider (under `Editors/text/x-kotlin/FoldManager`, because
 * [FoldTypeProvider] is `@MimeLocation(subfolderName = "FoldManager")`) the codes of
 * the [FoldType]s below are **deliberately identical** to those visitor keys, so the
 * matched, properly labelled/templated Kotlin types are used and the generic fallback
 * is bypassed (no duplicate folds).
 *
 * Registering the provider also makes these fold types appear in
 * *Tools ▸ Options ▸ Editor ▸ Folding*, mirroring the Java editor's behaviour.
 *
 * Annotation processing is disabled in this project (`-proc:none`), so the usual
 * `@MimeRegistration(service = FoldTypeProvider.class)` cannot be used; the provider
 * is registered manually in `layer.xml` instead.
 */
class KotlinFoldTypeProvider : FoldTypeProvider {

    companion object {

        /**
         * Folds the leading `import` list. Code `imports` matches the key produced by
         * `KotlinFoldingVisitor.visitImportList`. Derived from [FoldType.IMPORT] so it
         * is grouped under "Imports" in the editor; collapsed text is `...`.
         */
        @JvmField
        val IMPORTS: FoldType =
            FoldType.IMPORT.derive("imports", "Imports", FoldTemplate(0, 0, "..."))

        /**
         * Folds comments — line comment runs, block comments and KDoc, which the
         * visitor lumps together under the key `comments`. A neutral `...` template
         * (no guarded chars) is used because a fixed `/**...*/`-style template would
         * mis-render line-comment or plain block-comment folds.
         */
        @JvmField
        val COMMENTS: FoldType =
            FoldType.DOCUMENTATION.derive("comments", "Comments", FoldTemplate(0, 0, "..."))

        /**
         * Folds brace-delimited code blocks (class bodies, function/expression bodies).
         * Code `codeblocks` matches the key produced by
         * `KotlinFoldingVisitor.visitClassBody`/`visitBlockExpression`. The range
         * includes the braces, so the `{...}` template keeps the outer `{` and `}`
         * visible (one guarded char on each side).
         */
        @JvmField
        val CODE_BLOCKS: FoldType =
            FoldType.CODE_BLOCK.derive("codeblocks", "Code blocks", FoldTemplate(1, 1, "{...}"))

        /** The Kotlin fold types, in the order shown in the folding options. */
        private val TYPES: List<FoldType> = listOf(IMPORTS, COMMENTS, CODE_BLOCKS)
    }

    /**
     * Returns the Kotlin [FoldType]s when [type] is [FoldType] (the only kind the fold
     * infrastructure asks for), or `null` for any other requested kind.
     *
     * @param type the value kind requested by the fold infrastructure
     * @return the immutable list of Kotlin fold types, or `null` if [type] is not
     *         [FoldType]
     */
    override fun getValues(type: Class<*>): Collection<FoldType>? =
        if (type == FoldType::class.java) TYPES else null

    /**
     * Whether these fold types are inherited by child MIME types. Kotlin has no
     * embedded child languages, so this returns `false` (matching the JavaScript/PHP
     * providers).
     *
     * @return `false`
     */
    override fun inheritable(): Boolean = false
}
