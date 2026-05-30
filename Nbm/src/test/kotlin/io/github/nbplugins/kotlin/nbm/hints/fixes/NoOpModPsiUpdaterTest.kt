/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.netbeans.junit.NbTestCase
import utils.KotlinTestCase

/**
 * Unit tests for [NoOpModPsiUpdater].
 *
 * Verifies that [NoOpModPsiUpdater.getWritable] returns the element unchanged and
 * [NoOpModPsiUpdater.getCaretOffset] returns 0.
 */
class NoOpModPsiUpdaterTest : KotlinTestCase("NoOpModPsiUpdater", "quickfixes") {

    /** getCaretOffset returns 0. */
    fun testGetCaretOffsetReturnsZero() {
        assertEquals(0, NoOpModPsiUpdater.getCaretOffset())
    }

    /** getWritable with a real PSI element returns the same element. */
    fun testGetWritableReturnsSameElement() {
        val file = dir.getFileObject("implementMembers.kt") ?: return
        val ktFile = KotlinPsiManager.getParsedFile(file) ?: return
        val element = ktFile.findElementAt(0) ?: return
        val result = NoOpModPsiUpdater.getWritable(element)
        assertSame(element, result)
    }
}
