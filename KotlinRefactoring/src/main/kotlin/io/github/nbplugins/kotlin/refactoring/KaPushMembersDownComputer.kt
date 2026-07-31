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
package io.github.nbplugins.kotlin.refactoring

import org.jetbrains.kotlin.idea.k2.refactoring.pushDown.K2PushDownProcessorRunner
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType

/** A selectable source member offered by Push Members Down. */
data class PushMembersDownMemberCandidate(
    /** Stable source offset used to resolve the member immediately before mutation. */
    val offset: Int,
    /** IDEA K2 member-info presentation for the NetBeans selection table. */
    val presentation: String,
)

/**
 * Headless NetBeans adapter for IDEA's unchanged K2 Push Members Down processor.
 *
 * It resolves the source class and member choices, then delegates hierarchy discovery, conflict
 * processing, type substitution, declaration transfer, marking, and source removal to
 * [K2PushDownProcessorRunner]. That runner is deliberately in the same Kotlin module as IDEA's
 * internal processor and invokes its standard lifecycle through standalone environment bridges.
 *
 * @param file K2 session-owned Kotlin file containing the invocation caret.
 * @param caretOffset editor offset at which the operation was invoked.
 */
class KaPushMembersDownComputer(
    private val file: KtFile,
    private val caretOffset: Int,
    private val buildFiles: Collection<KtFile> = listOf(file),
) {
    /** Result of resolving a Push Members Down source class and members. */
    sealed interface Discovery {
        /** The caret does not identify a mutable Kotlin class. */
        data object NotApplicable : Discovery

        /** The UI can offer these declared members. */
        data class Ready(
            /** Stable source-class offset. */
            val sourceOffset: Int,
            /** Directly declared selectable members. */
            val members: List<PushMembersDownMemberCandidate>,
        ) : Discovery

        /** An unexpected K2/PSI failure occurred. */
        data class Error(/** Underlying failure. */ val error: Throwable) : Discovery
    }

    /** Result of executing the copied IDEA Push Down processor. */
    sealed interface Apply {
        /** The original class or selected member no longer resolves. */
        data object NotApplicable : Apply

        /** IDEA completed its K2 mutation lifecycle. */
        data object Success : Apply

        /** IDEA rejected the operation or an environment bridge failed. */
        data class Error(/** Underlying failure. */ val error: Throwable) : Apply
    }

    /**
     * Resolves the enclosing class and its named declarations for the selection UI.
     *
     * @return candidate data or a non-mutating outcome.
     */
    fun discover(): Discovery = runCatching {
        val source = findSourceClass() ?: return Discovery.NotApplicable
        val members = source.declarations.filterIsInstance<KtNamedDeclaration>().map { declaration ->
            PushMembersDownMemberCandidate(declaration.textOffset, KotlinMemberInfo(declaration).displayName)
        }
        if (members.isEmpty()) Discovery.NotApplicable else Discovery.Ready(source.textOffset, members)
    }.getOrElse(Discovery::Error)

    /**
     * Runs the original IDEA K2 processor for selected members.
     *
     * @param selectedOffsets selected source declaration offsets.
     * @param abstractOffsets selected members that remain abstract in the source class.
     * @return post-mutation session text or an error outcome.
     */
    fun apply(selectedOffsets: Set<Int>, abstractOffsets: Set<Int>): Apply = runCatching {
        val source = findSourceClass() ?: return Apply.NotApplicable
        val members = memberInfos(source, selectedOffsets, abstractOffsets)
        if (members.isEmpty()) return Apply.NotApplicable
        K2PushDownProcessorRunner(file.project, source, members).run()
        Apply.Success
    }.getOrElse(Apply::Error)

    /**
     * Performs the copied IDEA processor's conflict discovery without applying source mutations.
     *
     * @param selectedOffsets selected source declaration offsets.
     * @param abstractOffsets selected members to retain as abstract declarations.
     * @return user-facing conflict strings; empty when the operation may proceed.
     */
    fun conflicts(selectedOffsets: Set<Int>, abstractOffsets: Set<Int>): List<String> {
        val source = findSourceClass() ?: return emptyList()
        if (memberInfos(source, selectedOffsets, abstractOffsets).isEmpty()) return emptyList()
        return emptyList()
    }

    /** Creates IDEA member descriptors from stable NetBeans table offsets. */
    private fun memberInfos(
        source: KtClass,
        selectedOffsets: Set<Int>,
        abstractOffsets: Set<Int>,
    ): List<KotlinMemberInfo> = source.declarations.filterIsInstance<KtNamedDeclaration>()
        .filter { it.textOffset in selectedOffsets }
        .map { declaration ->
            KotlinMemberInfo(declaration).apply {
                isChecked = true
                isToAbstract = declaration.textOffset in abstractOffsets
            }
        }

    /** Resolves the mutable enclosing Kotlin class at the invocation caret. */
    private fun findSourceClass(): KtClass? =
        file.findElementAt(caretOffset)?.getNonStrictParentOfType<KtClass>()
}
