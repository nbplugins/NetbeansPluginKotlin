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
package io.github.nbplugins.kotlin.nbm.projectwizard

import org.netbeans.junit.NbTestCase
import org.openide.WizardDescriptor
import javax.swing.SwingUtilities

/**
 * Tests for [KotlinProjectWizardIterator]: panel navigation and variant wiring of the three
 * concrete iterators registered in the layer.
 */
class KotlinProjectWizardIteratorTest : NbTestCase("KotlinProjectWizardIteratorTest") {

    /** Every concrete iterator has exactly one panel and cannot navigate. */
    fun testSinglePanelNavigation() {
        for ((variant, iterator) in iterators()) {
            initialize(iterator)

            assertNotNull("$variant must provide a panel", iterator.current())
            assertFalse("$variant must have no next step", iterator.hasNext())
            assertFalse("$variant must have no previous step", iterator.hasPrevious())
            assertEquals("1. Name and Location", iterator.name())
        }
    }

    /** The panel of each iterator stores a request with the matching variant. */
    fun testVariantIsWrittenIntoRequest() {
        for ((variant, iterator) in iterators()) {
            val wizard = initialize(iterator)
            iterator.current().readSettings(wizard)
            iterator.current().storeSettings(wizard)

            val request = wizard.getProperty(KotlinProjectWizardPanel.KEY_REQUEST) as KotlinProjectRequest
            assertEquals("iterator for $variant must request $variant", variant, request.variant)
        }
    }

    /** `uninitialize` resets the iterator so it can be shown again. */
    fun testUninitializeResetsIterator() {
        val iterator = KotlinApplicationWizardIterator()
        val wizard = initialize(iterator)
        iterator.uninitialize(wizard)

        try {
            iterator.current()
            fail("current() must fail before initialize()")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("initialize"))
        }
    }

    /** Concrete layer iterators are instantiable through their public no-argument constructor. */
    fun testLayerIteratorsAreInstantiable() {
        val classes = listOf(
            "io.github.nbplugins.kotlin.nbm.projectwizard.KotlinApplicationWizardIterator",
            "io.github.nbplugins.kotlin.nbm.projectwizard.KotlinLibraryWizardIterator",
            "io.github.nbplugins.kotlin.nbm.projectwizard.KotlinScriptProjectWizardIterator",
        )
        for (className in classes) {
            val instance = Class.forName(className).getDeclaredConstructor().newInstance()
            assertTrue(
                "$className must implement InstantiatingIterator",
                instance is WizardDescriptor.InstantiatingIterator<*>,
            )
        }
    }

    /**
     * Initializes an iterator with a wizard descriptor and materializes the panel component on
     * the event dispatch thread.
     */
    private fun initialize(iterator: KotlinProjectWizardIterator): WizardDescriptor {
        val wizard = WizardDescriptor(iterator)
        iterator.initialize(wizard)
        SwingUtilities.invokeAndWait { iterator.current().getComponent() }
        return wizard
    }

    /**
     * @return every concrete iterator together with the variant it must create
     */
    private fun iterators(): List<Pair<ProjectVariant, KotlinProjectWizardIterator>> = listOf(
        ProjectVariant.APPLICATION to KotlinApplicationWizardIterator(),
        ProjectVariant.LIBRARY to KotlinLibraryWizardIterator(),
        ProjectVariant.SCRIPT to KotlinScriptProjectWizardIterator(),
    )
}
