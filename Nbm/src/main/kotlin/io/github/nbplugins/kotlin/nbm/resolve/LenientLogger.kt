/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.openapi.diagnostic.Logger
import java.util.logging.Level

/**
 * Real (not faked) fix for a standalone-environment-wide gap: IntelliJ's `DefaultLogger.error()`
 * unconditionally throws an `AssertionError` unless a real `Logger.Factory` is installed on the
 * application — which this plugin's `MockApplication` never does. Any bundled IDEA code calling
 * `LOG.error(...)` (a routine diagnostic call in real IntelliJ, where a proper logger just writes
 * to idea.log) becomes a hard crash here instead. First observed via Move Declaration (E9.7)'s
 * external-usage retargeting (`K2ReferenceMutateService` hitting a "requested stubbed spine"
 * `LOG.error` deep in `PsiFileImpl`), but this affects every other bundled-code call site too.
 *
 * [install] must run before any bundled IDEA class calls [Logger.getInstance] for the first time
 * (i.e. as early as possible in [KotlinAnalysisAPISession.initApplicationEnvironment]) — otherwise
 * already-cached `Logger` instances keep using [com.intellij.openapi.diagnostic.DefaultLogger].
 */
object LenientLoggerFactory : Logger.Factory {
    @Volatile private var installed = false

    /** Installs this factory as the platform's [Logger.Factory], once. */
    fun install() {
        if (installed) return
        Logger.setFactory(LenientLoggerFactory::class.java)
        installed = true
    }

    override fun getLoggerInstance(category: String): Logger = LenientLogger(category)
}

/**
 * [Logger] implementation delegating to `java.util.logging`, matching [DefaultLogger]'s behaviour
 * except that [error] logs instead of throwing — the one behavioural difference this environment
 * needs, since nothing here can rely on a real IDE's "report to JetBrains" error-reporting UI.
 */
private class LenientLogger(category: String) : Logger() {
    private val delegate = java.util.logging.Logger.getLogger(category)

    override fun isDebugEnabled(): Boolean = delegate.isLoggable(Level.FINE)
    override fun debug(message: String) { delegate.fine(message) }
    override fun debug(message: String, t: Throwable?) { delegate.log(Level.FINE, message, t) }
    override fun info(message: String) { delegate.info(message) }
    override fun info(message: String, t: Throwable?) { delegate.log(Level.INFO, message, t) }
    override fun warn(message: String, t: Throwable?) { delegate.log(Level.WARNING, message, t) }
    override fun error(message: String, t: Throwable?, vararg details: String) {
        delegate.log(Level.SEVERE, message + if (details.isNotEmpty()) " " + details.joinToString() else "", t)
    }
    override fun setLevel(level: org.apache.log4j.Level) {}
}
