/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.refactoring.KaExtractSuperComputer
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/** Unit tests for Extract Interface/Extract Superclass source-class discovery. */
class KaExtractSuperComputerTest : KotlinTestCase("KaExtractSuperComputerTest", "extractSuper") {
    /** Discovers the class member at a caret positioned in its body. */
    fun testDiscover_insideClass_returnsMember() {
        val session = io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("simple")?.getFileObject("file.kt") ?: return
        val source = file.asText()
        val caret = source.indexOf("greet").also { if (it < 0) return }
        val ktFile = session.getKtFileForPath(file.path) ?: return

        val result = KaExtractSuperComputer(ktFile, caret).discover()

        assertTrue("Expected members for source class, got $result", result is KaExtractSuperComputer.Discovery.Ready)
        result as KaExtractSuperComputer.Discovery.Ready
        assertEquals("Greeter", result.sourceName)
        assertTrue("Expected greet candidate", result.members.any { it.presentation.contains("greet") })
    }

    /** Rejects a caret outside of a class without mutating PSI. */
    fun testDiscover_outsideClass_isNotApplicable() {
        val session = io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) return
        val file = dir.getFileObject("outside")?.getFileObject("file.kt") ?: return
        val ktFile: KtFile = session.getKtFileForPath(file.path) ?: return

        val result = KaExtractSuperComputer(ktFile, 0).discover()

        assertSame(KaExtractSuperComputer.Discovery.NotApplicable, result)
    }
}
