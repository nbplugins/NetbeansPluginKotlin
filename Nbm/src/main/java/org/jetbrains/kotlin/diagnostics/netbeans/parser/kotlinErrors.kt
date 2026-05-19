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
package org.jetbrains.kotlin.diagnostics.netbeans.parser

import org.netbeans.modules.csl.api.Error.Badging
import org.netbeans.modules.csl.api.Severity
import org.openide.filesystems.FileObject
import com.intellij.psi.PsiErrorElement

/**
 * Placeholder for the K1-era diagnostic wrapper. Never instantiated at runtime (K2-only since C10).
 * Kept as a compile-time type for legacy quick-fix classes pending removal in the E-track.
 */
class KotlinError private constructor() : Badging {
    override fun showExplorerBadge() = false
    override fun getDisplayName() = ""
    override fun getDescription() = ""
    override fun getKey() = ""
    override fun getFile(): FileObject = throw UnsupportedOperationException()
    override fun getStartPosition() = 0
    override fun getEndPosition() = 0
    override fun isLineError() = false
    override fun getSeverity() = Severity.ERROR
    override fun getParameters() = null
}

class KotlinSyntaxError(private val psiError: PsiErrorElement, val fileObject: FileObject) : Badging {
    override fun showExplorerBadge() = true

    override fun getDisplayName(): String = psiError.errorDescription ?: ""

    override fun getDescription() = ""

    override fun getKey() = ""

    override fun getFile() = fileObject

    override fun getStartPosition() = psiError.textRange.startOffset

    override fun getEndPosition() = psiError.textRange.endOffset

    override fun isLineError() = false

    override fun getSeverity() = Severity.ERROR

    override fun getParameters() = null
}