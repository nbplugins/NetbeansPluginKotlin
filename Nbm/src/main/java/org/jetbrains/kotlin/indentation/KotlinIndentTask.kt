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
package org.jetbrains.kotlin.indentation

import org.jetbrains.kotlin.formatting.KotlinIndentStrategy
import org.jetbrains.kotlin.log.KotlinLogger
import org.netbeans.modules.editor.indent.spi.Context
import org.netbeans.modules.editor.indent.spi.IndentTask

class KotlinIndentTask(val context: Context) : IndentTask {

    override fun reindent() {
        // B2.0 (compiler-only): the bundled KotlinFormatter compiled against kotlin-compiler
        // 1.3.72 fails on every key with NoSuchFieldError: KtTokens.FUN_KEYWORD because the
        // field's type changed in 1.9.25 (KtKeywordToken → KtModifierKeywordToken). Silently
        // swallow so editing isn't disrupted by SEVERE popups; auto-indent is degraded to NB's
        // default behaviour. Re-enable when KotlinFormatter is recompiled against 1.9.25.
        try {
            KotlinIndentStrategy(context).addIndent()
        } catch (e: NoSuchFieldError) {
            KotlinLogger.INSTANCE.logWarning("KotlinIndentStrategy disabled by 1.9.25 binary incompat: ${e.message}")
        } catch (e: NoClassDefFoundError) {
            KotlinLogger.INSTANCE.logWarning("KotlinIndentStrategy disabled by 1.9.25 binary incompat: ${e.message}")
        }
    }

    override fun indentLock() = null
}