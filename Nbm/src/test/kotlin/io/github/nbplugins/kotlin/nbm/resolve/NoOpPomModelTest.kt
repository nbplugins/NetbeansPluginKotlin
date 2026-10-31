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

import com.intellij.openapi.project.Project
import com.intellij.pom.PomModel
import com.intellij.pom.PomModelAspect
import com.intellij.pom.PomTransaction
import com.intellij.pom.event.PomModelEvent
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.PsiElement
import com.intellij.util.IncorrectOperationException
import utils.KotlinTestCase

/**
 * Unit tests for [NoOpPomModel].
 *
 * The stub is what lets [com.intellij.psi.impl.source.tree.ChangeUtil] mutate the PSI of a
 * standalone session at all, and its cache invalidation is what makes a resolution performed right
 * after an edit see the edited tree. The invalidation's effect on resolution is covered end to end
 * by `KotlinReferencesSearchExecutorTest`; these tests pin the stub's own contract.
 */
class NoOpPomModelTest : KotlinTestCase("NoOpPomModel test", "j2k") {

    /**
     * Verifies that the model answers the only aspect [com.intellij.psi.impl.source.tree.ChangeUtil]
     * asks for and reports nothing for the others.
     */
    fun testReturnsTreeAspectOnly() {
        val model = NoOpPomModel(ideaProject())
        val treeAspect = model.getModelAspect(TreeAspect::class.java)
        assertNotNull("TreeAspect must be returned", treeAspect)
        assertSame("the same TreeAspect must be returned on every call", treeAspect, model.getModelAspect(TreeAspect::class.java))
        assertNull("an unknown aspect must not be reported", model.getModelAspect(PomModelAspect::class.java))
    }

    /**
     * Verifies that a transaction is actually executed: [NoOpPomModel.runTransaction] must call the
     * delegate, not silently drop the edit.
     */
    fun testRunsTheTransaction() {
        val model = NoOpPomModel(ideaProject())
        var runs = 0
        model.runTransaction(TestTransaction { runs++ })
        assertEquals("the transaction must run exactly once", 1, runs)
    }

    /**
     * Verifies that a failing transaction still propagates its failure — the cache invalidation in
     * the `finally` block must not swallow it.
     */
    fun testPropagatesTransactionFailure() {
        val model = NoOpPomModel(ideaProject())
        try {
            model.runTransaction(TestTransaction { throw IncorrectOperationException("boom") })
            fail("the transaction's exception must propagate")
        } catch (expected: IncorrectOperationException) {
            assertEquals("boom", expected.message)
        }
    }

    /**
     * Verifies that the model is registered as the project's [PomModel] service, which is how
     * `ChangeUtil` finds it.
     */
    fun testRegisteredAsProjectService() {
        val model = com.intellij.pom.PomManager.getModel(ideaProject())
        assertTrue("the project's PomModel must be a NoOpPomModel", model is NoOpPomModel)
    }

    /** Listener registration is a no-op and must not throw. */
    fun testListenersAreIgnored() {
        val ideaProject = ideaProject()
        val model = NoOpPomModel(ideaProject)
        val listener = object : com.intellij.pom.event.PomModelListener {
            override fun modelChanged(event: PomModelEvent) { /* ignored */ }
            override fun isAspectChangeInteresting(aspect: PomModelAspect): Boolean = false
        }
        model.addModelListener(listener)
        model.addModelListener(listener, ideaProject)
        model.removeModelListener(listener)
    }

    /**
     * Returns the IntelliJ project of the test project's analysis session.
     *
     * @return the project the standalone K2 session was built for
     */
    private fun ideaProject(): Project {
        val session = KotlinAnalysisAPISession.getSession(project)
        assertNotNull("the analysis session must exist", session)
        return session!!.session.project
    }

    /**
     * A [PomTransaction] whose behaviour is supplied by a lambda.
     *
     * @param action the body of [run]
     */
    private class TestTransaction(private val action: () -> Unit) : PomTransaction {
        override fun getAccumulatedEvent(): PomModelEvent = error("not used by the tests")
        override fun run() = action()
        override fun getChangeScope(): PsiElement = error("not used by the tests")
    }
}
