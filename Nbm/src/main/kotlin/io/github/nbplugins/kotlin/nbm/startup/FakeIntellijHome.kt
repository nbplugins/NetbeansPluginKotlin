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
package io.github.nbplugins.kotlin.nbm.startup

import org.openide.modules.OnStop
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Creates and tears down a minimal fake IntelliJ home directory in `/tmp`.
 *
 * [KotlinEnvironment] and [buildStandaloneAnalysisAPISession] both call
 * `getOrCreateApplicationEnvironmentForProduction()` which invokes
 * `PathManager.getHomePath()` — it requires `bin/idea.properties` to exist under
 * `idea.home.path`. NetBeans is not an IntelliJ IDE, so we synthesise a minimal layout.
 *
 * Call [startUp] at plugin init (via [StartingUp]) and [shutDown] at normal shutdown
 * (via [ShuttingDown], annotated with [@OnStop][OnStop]).
 *
 * If NetBeans crashes the temp directory may be left in `/tmp` — that is acceptable.
 */
object FakeIntellijHome {

    private const val BUILD_NUMBER = "IC-242.26775.26"

    @Volatile private var homePath: Path? = null

    /**
     * Registers this as a NetBeans startup hook. Call [run] (or construct this class and
     * let the module system call it) from [org.jetbrains.kotlin.installer.KotlinInstaller.restored].
     */
    class StartingUp : Runnable {
        override fun run() = startUp()
    }

    /**
     * NetBeans [@OnStop][OnStop] hook: called automatically on normal IDE shutdown.
     * Deletes the temp directory created by [startUp] and clears `idea.home.path`.
     */
    @OnStop
    class ShuttingDown : Callable<Boolean> {
        override fun call(): Boolean {
            shutDown()
            return true
        }
    }

    /**
     * Creates a temporary directory under `/tmp` with the minimal layout required by
     * `PathManager.getHomePath()`:
     * - `build.txt` containing the build number
     * - `bin/idea.properties` (empty)
     *
     * Sets `idea.home.path` to the created directory. Idempotent: a second call is a no-op.
     */
    @Synchronized
    fun startUp() {
        if (homePath != null) return
        val home = Files.createTempDirectory("nbkotlin-intellij-home-")
        Files.writeString(home.resolve("build.txt"), BUILD_NUMBER)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        homePath = home
    }

    /**
     * Deletes the temp directory created by [startUp] and clears `idea.home.path`.
     * Idempotent: a second call (or a call before [startUp]) is a no-op.
     */
    @Synchronized
    fun shutDown() {
        val home = homePath ?: return
        home.toFile().deleteRecursively()
        System.clearProperty("idea.home.path")
        homePath = null
    }
}
