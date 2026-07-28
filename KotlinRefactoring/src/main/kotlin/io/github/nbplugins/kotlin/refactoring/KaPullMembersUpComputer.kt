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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo
import org.jetbrains.kotlin.idea.refactoring.memberInfo.toJavaMemberInfo
import org.jetbrains.kotlin.idea.refactoring.resolveDirectSupertypes
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType

/** A direct Kotlin supertype available as a Pull Members Up target. */
data class PullMembersUpTarget(
    /** Stable PSI offset used to find the target immediately before mutation. */
    val offset: Int,
    /** Absolute virtual-file path of the target declaration. */
    val filePath: String,
    /** User-facing type name. */
    val name: String,
)

/** A source member offered in the Pull Members Up selection table. */
data class PullMembersUpMemberCandidate(
    /** Stable PSI offset used to find the member immediately before mutation. */
    val offset: Int,
    /** K2-rendered member label. */
    val presentation: String,
    /** Whether the member is selectable. */
    val enabled: Boolean,
    /** Whether the member is initially requested as abstract. */
    val makeAbstract: Boolean,
)

/** Immutable choices supplied by the Pull Members Up UI. */
data class PullMembersUpRequest(
    /** Offset of the source class selected at discovery time. */
    val sourceOffset: Int,
    /** Offset of the selected target supertype. */
    val targetOffset: Int,
    /** Absolute virtual-file path of the selected target supertype. */
    val targetFilePath: String,
    /** Offsets of source members selected for movement. */
    val selectedOffsets: Set<Int>,
    /** Selected members to leave abstract in the source class. */
    val abstractOffsets: Set<Int>,
)

/** A conflict shown to the user before Pull Members Up can change source code. */
data class PullMembersUpConflict(
    /** Source offset of the involved declaration. */
    val offset: Int,
    /** Human-readable explanation of the conflict. */
    val message: String,
)

/**
 * Headless adapter for IDEA's K2 Pull Members Up engine.
 *
 * @param file Kotlin file containing the caret and source class.
 * @param caretOffset offset of the invocation caret in [file].
 * @param targetFile refreshed target PSI file when the selected supertype is in another file.
 */
class KaPullMembersUpComputer @JvmOverloads constructor(
    private val file: KtFile,
    private val caretOffset: Int,
    private val targetFile: KtFile? = null,
) {
    /** Result of source/target/member discovery. */
    sealed interface Discovery {
        /** The caret has no Kotlin class with a usable Kotlin supertype. */
        data object NotApplicable : Discovery

        /** The selection dialog can be populated from these candidates. */
        data class Ready(
            /** Offset of the source class. */
            val sourceOffset: Int,
            /** Direct Kotlin supertypes that can receive members. */
            val targets: List<PullMembersUpTarget>,
            /** Members declared directly by the source class. */
            val members: List<PullMembersUpMemberCandidate>,
        ) : Discovery {
            /**
             * Creates a backend request from UI offsets.
             *
             * @param target selected target supertype.
             * @param selectedOffsets selected source member offsets.
             * @param abstractOffsets selected source members to make abstract.
             * @return request for [KaPullMembersUpComputer.checkConflicts] or apply.
             */
            fun toRequest(
                target: PullMembersUpTarget,
                selectedOffsets: Set<Int>,
                abstractOffsets: Set<Int>,
            ): PullMembersUpRequest = PullMembersUpRequest(
                sourceOffset,
                target.offset,
                target.filePath,
                selectedOffsets,
                abstractOffsets,
            )
        }

        /** An unexpected PSI or K2 error occurred. */
        data class Error(/** Underlying failure. */ val error: Throwable) : Discovery
    }

    /** Result of pre-mutation conflict detection. */
    sealed interface ConflictCheck {
        /** The requested movement has no local target-member collisions. */
        data object Clear : ConflictCheck

        /** The dialog must show these conflicts before apply is enabled. */
        data class Conflicts(/** Detected conflict details. */ val items: List<PullMembersUpConflict>) : ConflictCheck

        /** The request no longer resolves against current PSI. */
        data object NotApplicable : ConflictCheck

        /** An unexpected PSI or K2 error occurred. */
        data class Error(/** Underlying failure. */ val error: Throwable) : ConflictCheck
    }

    /** Result of executing the IDEA member-move engine. */
    sealed interface Apply {
        /** The request no longer resolves against current PSI. */
        data object NotApplicable : Apply

        /** IDEA changed the source and target PSI files. */
        data class Success(
            /** Post-refactoring source file text. */
            val sourceText: String,
            /** Post-refactoring target file text. */
            val targetText: String,
        ) : Apply

        /** IDEA rejected the request or mutation failed. */
        data class Error(/** Underlying failure. */ val error: Throwable) : Apply
    }

    /**
     * Resolves the source class, its direct Kotlin supertypes, and declared members.
     *
     * @return dialog candidates or a non-mutating outcome.
     */
    fun discover(): Discovery = runCatching {
        val source = findSourceClass() ?: return Discovery.NotApplicable
        val targets = source.resolveDirectSupertypes()
            .filterIsInstance<KtClassOrObject>()
            .map { target ->
                PullMembersUpTarget(
                    target.textOffset,
                    target.containingKtFile.virtualFile?.path.orEmpty(),
                    target.name ?: "<anonymous>",
                )
            }
            .sortedBy(PullMembersUpTarget::name)
        if (targets.isEmpty()) return Discovery.NotApplicable
        val members = source.declarations.filterIsInstance<KtNamedDeclaration>().map { declaration ->
            val info = KotlinMemberInfo(declaration)
            PullMembersUpMemberCandidate(declaration.textOffset, info.displayName, true, false)
        }
        Discovery.Ready(source.textOffset, targets, members)
    }.getOrElse(Discovery::Error)

    /**
     * Finds target-member name collisions before the user confirms a refactoring.
     *
     * @param request current UI selection.
     * @return clear, conflicting, or non-mutating outcome.
     */
    fun checkConflicts(request: PullMembersUpRequest): ConflictCheck = runCatching {
        val (_, target, selected) = resolve(request) ?: return ConflictCheck.NotApplicable
        val conflicts = selected.mapNotNull { member ->
            val name = member.name ?: return@mapNotNull null
            val targetMember = target.declarations.filterIsInstance<KtNamedDeclaration>().firstOrNull { it.name == name }
                ?: return@mapNotNull null
            PullMembersUpConflict(
                member.textOffset,
                "Class ${target.name ?: "target"} already contains member $name.",
            )
        }
        if (conflicts.isEmpty()) ConflictCheck.Clear else ConflictCheck.Conflicts(conflicts)
    }.getOrElse(ConflictCheck::Error)

    /**
     * Invokes IDEA's real Pull Up processor after a conflict-free user confirmation.
     *
     * @param request current UI selection.
     * @return mutated file texts or a non-mutating outcome.
     */
    fun apply(request: PullMembersUpRequest): Apply = runCatching {
        val (source, target, selected) = resolve(request) ?: return Apply.NotApplicable
        val sourceLight = source.toLightClass() ?: return Apply.NotApplicable
        val targetLight = target.toLightClass() ?: return Apply.NotApplicable
        val infos = selected.map { declaration ->
            KotlinMemberInfo(declaration).apply {
                isChecked = true
                isToAbstract = declaration.textOffset in request.abstractOffsets
            }.toJavaMemberInfo()
        }.filterNotNull().toTypedArray()
        if (infos.isEmpty()) return Apply.NotApplicable
        PullUpProcessor(sourceLight, targetLight, infos, DocCommentPolicy(0)).moveMembersToBase()
        Apply.Success(file.text, target.containingKtFile.text)
    }.getOrElse(Apply::Error)

    /** Resolves fresh source, target, and selected declarations to avoid stale dialog PSI. */
    private fun resolve(request: PullMembersUpRequest): Triple<KtClassOrObject, KtClassOrObject, List<KtNamedDeclaration>>? {
        val source = file.findElementAt(request.sourceOffset)?.getNonStrictParentOfType<KtClassOrObject>()
            ?: findSourceClass()
            ?: return null
        val target = source.resolveDirectSupertypes().filterIsInstance<KtClassOrObject>()
            .firstOrNull {
                it.textOffset == request.targetOffset &&
                    it.containingKtFile.virtualFile?.path == request.targetFilePath
            }
            ?: targetFile?.takeIf { it.virtualFile?.path == request.targetFilePath }
                ?.findElementAt(request.targetOffset)
                ?.getNonStrictParentOfType<KtClassOrObject>()
            ?: return null
        val selected = source.declarations.filterIsInstance<KtNamedDeclaration>()
            .filter { it.textOffset in request.selectedOffsets }
        return if (selected.isEmpty()) null else Triple(source, target, selected)
    }

    /** Locates the enclosing Kotlin class/object at the invocation caret. */
    private fun findSourceClass(): KtClassOrObject? =
        file.findElementAt(caretOffset)?.getNonStrictParentOfType<KtClassOrObject>()
}
