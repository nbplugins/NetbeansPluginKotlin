// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Stub: replaces formatter-heavy original with simple alphabetical comparator.
package org.jetbrains.kotlin.idea.base.psi.imports

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.ImportPath

@ApiStatus.Internal
class KotlinImportPathComparator private constructor() : Comparator<ImportPath> {
    override fun compare(import1: ImportPath, import2: ImportPath): Int =
        import1.pathStr.compareTo(import2.pathStr)

    companion object {
        fun create(file: KtFile): KotlinImportPathComparator = KotlinImportPathComparator()
    }
}
