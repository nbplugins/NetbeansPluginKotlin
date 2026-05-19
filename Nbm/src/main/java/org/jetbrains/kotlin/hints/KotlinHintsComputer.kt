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
package org.jetbrains.kotlin.hints

import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.psi.*
import org.netbeans.modules.csl.api.Hint

/** Visits the whole file and collects file-wide K2 hints. */
class KotlinHintsComputer(val parserResult: KotlinParserResult) : KtVisitor<Unit, Any?>() {

    val hints = arrayListOf<Hint>()

    override fun visitKtFile(ktFile: KtFile, data: Any?) {
        ktFile.acceptChildren(this)
    }

    override fun visitKtElement(element: KtElement, data: Any?) {
        element.acceptChildren(this)
    }
}
