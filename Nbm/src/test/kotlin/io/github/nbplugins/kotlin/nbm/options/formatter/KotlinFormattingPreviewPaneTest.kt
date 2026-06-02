/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.options.formatter

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinFormattingPreviewPane].
 *
 * <p>All tests run with no open project so that formatting falls back to the
 * unmodified raw code path — this avoids a dependency on a live Kotlin project
 * in the test environment.
 */
class KotlinFormattingPreviewPaneTest : NbTestCase("KotlinFormattingPreviewPaneTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    /** The preview.kt resource must be present and non-empty. */
    fun testRawCodeLoaded() {
        val pane = KotlinFormattingPreviewPane { _ -> }
        assertTrue("preview.kt resource must load non-empty code", pane.getRawCode().isNotEmpty())
        assertTrue("raw code must contain Kotlin source", pane.getRawCode().contains("class"))
    }

    /**
     * With no open project [refreshNow] falls back to showing the raw code
     * rather than throwing.
     */
    fun testRefreshWithNoProjectShowsRawCode() {
        val pane = KotlinFormattingPreviewPane { _ -> }
        pane.refreshNow()
        assertEquals(pane.getRawCode(), pane.getText())
    }

    /**
     * [collectSettings] must NOT be called when there is no open project —
     * the code path returns early before formatting begins.
     */
    fun testCollectSettingsNotCalledWithoutProject() {
        var callCount = 0
        val pane = KotlinFormattingPreviewPane { _ -> callCount++ }
        pane.refreshNow()
        assertEquals(0, callCount)
    }
}
