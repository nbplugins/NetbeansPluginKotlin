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

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.usages.K2MoveRenameUsageInfo
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * Lifecycle-free K2 adapter for the portable subset of Move Nested Kotlin Member.
 *
 * IDEA's generic Java Move Members processor needs indexed Java PSI, light classes, Usage View, and
 * command lifecycle services unavailable in the standalone container. This adapter instead retains
 * IDEA's K2 reference-retargeting engine while only accepting relocations whose receiver change is
 * mechanically safe: nested classes and companion-object functions/properties.
 *
 * @param file K2 session-owned file containing the invocation caret.
 * @param caretOffset editor offset at which the operation was invoked.
 */
class KaMoveNestedMemberComputer(
    private val file: KtFile,
    private val caretOffset: Int,
) {
    /** Result of resolving a supported declaration. */
    sealed interface Outcome {
        /** The caret does not identify a nested class or companion member with safe semantics. */
        data object NotApplicable : Outcome

        /** The UI may offer a target class/object for this declaration. */
        data class Ready(
            /** Range to preview in the declaration file. */
            val declarationRange: TextRange,
            /** Simple declaration name. */
            val declarationName: String,
            /** Whether the declaration must stay in a companion object. */
            val requiresCompanionTarget: Boolean,
            /** Source file path, used by transaction-backed persistence. */
            val sourceFilePath: String,
            /** Kotlin package containing the source declaration. */
            val sourcePackageName: String,
            /** Top-level-to-owner source class/object path used to expand the target tree. */
            val sourceContainerPath: List<String>,
        ) : Outcome

        /** An unexpected PSI or K2 failure occurred. */
        data class Error(/** Underlying failure. */ val error: Throwable) : Outcome
    }

    /** One named class/object that the controller may offer as a move target. */
    data class TargetCandidate(
        /** Absolute path of the K2 session file containing this target. */
        val filePath: String,
        /** Stable offset inside [filePath] used to resolve the target immediately before apply. */
        val offset: Int,
        /** Readable simple type label shown by the passive NetBeans view. */
        val presentation: String,
        /** Kotlin package containing the target, empty for the default package. */
        val packageName: String,
        /** Top-level-to-leaf Kotlin class/object path used to build the passive target tree. */
        val containerPath: List<String>,
        /** Whether the move destination is this target's companion object. */
        val requiresCompanionTarget: Boolean,
    )

    /** Result of a pre-mutation validation pass. */
    sealed interface ConflictCheck {
        /** The requested target is valid for this portable move subset. */
        data object Clear : ConflictCheck

        /** The operation must not mutate PSI; messages are ready for NetBeans preview. */
        data class Conflicts(/** Human-readable validation failures. */ val messages: List<String>) : ConflictCheck

        /** The declaration or target no longer resolves. */
        data object NotApplicable : ConflictCheck

        /** An unexpected PSI or K2 failure occurred. */
        data class Error(/** Underlying failure. */ val error: Throwable) : ConflictCheck
    }

    /** Result of executing one K2-native nested move. */
    sealed interface Apply {
        /** The declaration or target no longer resolves. */
        data object NotApplicable : Apply

        /** Validation failed without changing standalone PSI. */
        data class Conflicts(/** Human-readable validation failures. */ val messages: List<String>) : Apply

        /** Final source, target, and external-usage texts to persist atomically. */
        data class Success(
            /** Every session file changed by the K2 move/retargeting operation. */
            val changedFiles: Map<String, String>,
            /** Final text of the declaration's original file. */
            val sourceText: String,
            /** Final text of the file containing the selected target class. */
            val targetText: String,
        ) : Apply

        /** An unexpected mutation or retargeting failure occurred. */
        data class Error(/** Underlying failure. */ val error: Throwable) : Apply
    }

    /**
     * Resolves the declaration at the invocation caret without modifying PSI.
     *
     * @return a supported-move description, a non-applicability result, or an analysis error.
     */
    fun compute(): Outcome = runCatching {
        val declaration = findDeclaration() ?: return Outcome.NotApplicable
        val kind = supportedKind(declaration) ?: return Outcome.NotApplicable
        val owner = declaration.getStrictParentOfType<KtClassOrObject>() ?: return Outcome.NotApplicable
        Outcome.Ready(
            declarationRange = declaration.textRange,
            declarationName = declaration.name ?: return Outcome.NotApplicable,
            requiresCompanionTarget = kind == Kind.COMPANION_MEMBER,
            sourceFilePath = declaration.containingKtFile.virtualFile?.path.orEmpty(),
            sourcePackageName = declaration.containingKtFile.packageFqName.asString(),
            sourceContainerPath = owner.parentsWithSelf
                .filterIsInstance<KtClassOrObject>()
                .mapNotNull(KtClassOrObject::getName)
                .toList()
                .asReversed(),
        )
    }.getOrElse(Outcome::Error)

    /**
     * Finds named class/object targets in [files] that can safely receive the declaration at the caret.
     *
     * The method is intentionally K2/PSI-only: it provides immutable data for a controller-owned
     * target chooser and never resolves NetBeans files, opens dialogs, or mutates source.
     *
     * @param files K2 session files to inspect for named class/object targets.
     * @return supported targets, sorted by their readable presentation; empty when not applicable.
     */
    fun discoverTargets(files: Iterable<KtFile>): List<TargetCandidate> = runCatching {
        val declaration = findDeclaration() ?: return emptyList()
        val kind = supportedKind(declaration) ?: return emptyList()
        val owner = declaration.getStrictParentOfType<KtClassOrObject>() ?: return emptyList()
        val excludedOwners = buildList {
            add(declaration)
            add(owner)
            declaration.getStrictParentOfType<KtObjectDeclaration>()
                ?.getStrictParentOfType<KtClassOrObject>()
                ?.let(::add)
        }.map { it.containingKtFile.virtualFile?.path to it.textOffset }.toSet()
        files.flatMap { candidateFile ->
            candidateFile.collectDescendantsOfType<KtClassOrObject>()
                .asSequence()
                .filter { target ->
                    target.name != null &&
                        (target.containingKtFile.virtualFile?.path to target.textOffset) !in excludedOwners
                }
                .filter { target ->
                    when (kind) {
                        Kind.NESTED_CLASS -> true
                        Kind.COMPANION_MEMBER -> target.companionObjects.singleOrNull() != null
                    }
                }
                .mapNotNull { target ->
                    target.containingKtFile.virtualFile?.path?.let { path ->
                        TargetCandidate(
                            filePath = path,
                            offset = target.textOffset,
                            presentation = target.name!!,
                            packageName = target.containingKtFile.packageFqName.asString(),
                            containerPath = target.parentsWithSelf
                                .filterIsInstance<KtClassOrObject>()
                                .mapNotNull(KtClassOrObject::getName)
                                .toList()
                                .asReversed(),
                            requiresCompanionTarget = kind == Kind.COMPANION_MEMBER,
                        )
                    }
                }
                .toList()
        }.distinctBy { it.filePath to it.offset }.sortedWith(compareBy(TargetCandidate::presentation, TargetCandidate::filePath))
    }.getOrElse { emptyList() }

    /**
     * Validates [targetOffset] in [targetFile] without changing source or target PSI.
     *
     * @param targetFile K2 session file containing the selected target class/object.
     * @param targetOffset stable offset inside that target.
     * @return a clear, conflicting, non-applicable, or error result.
     */
    fun checkConflicts(targetFile: KtFile, targetOffset: Int): ConflictCheck = runCatching {
        val declaration = findDeclaration() ?: return ConflictCheck.NotApplicable
        val target = resolveTarget(targetFile, targetOffset) ?: return ConflictCheck.NotApplicable
        validationMessages(declaration, target).let { messages ->
            if (messages.isEmpty()) ConflictCheck.Clear else ConflictCheck.Conflicts(messages)
        }
    }.getOrElse(ConflictCheck::Error)

    /**
     * Moves the supported declaration into [targetFile]'s target class at [targetOffset].
     *
     * The method only mutates session PSI. Its caller persists [Apply.Success.changedFiles] through
     * `KotlinRefactoringTransaction`, which supplies atomic rollback and Undo Last Refactoring.
     *
     * @param targetFile K2 session file containing the selected target class/object.
     * @param targetOffset stable offset inside that target.
     * @return final file text, conflicts, non-applicability, or an error.
     */
    fun move(targetFile: KtFile, targetOffset: Int): Apply = runCatching {
        val declaration = findDeclaration() ?: return Apply.NotApplicable
        val target = resolveTarget(targetFile, targetOffset) ?: return Apply.NotApplicable
        val messages = validationMessages(declaration, target)
        if (messages.isNotEmpty()) return Apply.Conflicts(messages)

        val usageInfos = KotlinMoveUsageSearchService.getInstance()?.findUsages(declaration).orEmpty()
            .filterIsInstance<KtReference>()
            .filter { reference -> !com.intellij.psi.util.PsiTreeUtil.isAncestor(declaration, reference.element, false) }
            .mapNotNull { reference ->
                val element = reference.element as? org.jetbrains.kotlin.psi.KtElement ?: return@mapNotNull null
                K2MoveRenameUsageInfo.Source(element, reference, declaration, false)
            }
        val usageFiles = usageInfos.mapNotNull { usage ->
            usage.element?.containingFile as? KtFile
        }.distinct()

        K2MoveRenameUsageInfo.markInternalUsages(declaration, declaration)
        val targetContainer = targetContainer(declaration, target) ?: return Apply.NotApplicable
        val sourceFile = declaration.containingKtFile
        val psiFactory = KtPsiFactory(targetContainer.project)
        val targetBody = targetContainer.body ?: run {
            targetContainer.add(psiFactory.createWhiteSpace(" "))
            targetContainer.add(psiFactory.createEmptyClassBody()) as? org.jetbrains.kotlin.psi.KtClassBody
                ?: return Apply.NotApplicable
        }
        val declarationName = declaration.name ?: return Apply.NotApplicable
        val targetRightBrace = targetBody.rBrace ?: return Apply.NotApplicable
        if (targetBody.declarations.isEmpty()) targetBody.addBefore(psiFactory.createNewLine(), targetRightBrace)
        // `KtClassBody.add()` accepts an arbitrary PSI element in standalone mode and can attach it
        // to JavaDummyHolder instead of the Kotlin body. Anchoring immediately before the real `}`
        // keeps the copy inside the destination KtFile and gives K2 a stable parent chain.
        val inserted = targetBody.addBefore(declaration.copy(), targetRightBrace) as? KtNamedDeclaration
            ?: return Apply.NotApplicable
        targetBody.addAfter(psiFactory.createNewLine(), inserted)
        val copied = targetBody.declarations.filterIsInstance<KtNamedDeclaration>()
            .lastOrNull { it === inserted || it.name == declarationName }
            ?: return Apply.NotApplicable
        val oldToNew = declaration.collectDescendantsOfType<KtNamedDeclaration>()
            .zip(copied.collectDescendantsOfType<KtNamedDeclaration>())
            .toMap(mutableMapOf<PsiElement, PsiElement>().apply { put(declaration, copied) })
        val sourceBody = declaration.getStrictParentOfType<KtClassOrObject>()?.body
        declaration.delete()
        normalizeEmptyBody(sourceBody)
        K2MoveRenameUsageInfo.retargetUsages(usageInfos, oldToNew)

        val changed = (listOf(sourceFile, targetFile) + usageFiles)
            .distinct()
            .mapNotNull { changedFile -> changedFile.virtualFile?.path?.let { it to changedFile.text } }
            .toMap()
        Apply.Success(changed, sourceFile.text, targetFile.text)
    }.getOrElse(Apply::Error)

    /** Restores one well-formed newline before an empty source body's closing brace. */
    private fun normalizeEmptyBody(body: org.jetbrains.kotlin.psi.KtClassBody?) {
        if (body == null || body.declarations.isNotEmpty()) return
        val whitespace = body.rBrace?.prevSibling as? PsiWhiteSpace ?: return
        if (whitespace.text == "\n") return
        whitespace.replace(KtPsiFactory(body.project).createWhiteSpace("\n"))
    }

    /** Resolves a declaration directly at the caret; usage-site invocation is deliberately excluded. */
    private fun findDeclaration(): KtNamedDeclaration? = sequenceOf(caretOffset, caretOffset + 1)
        .mapNotNull(file::findElementAt)
        .flatMap { it.parentsWithSelf }
        .filterIsInstance<KtNamedDeclaration>()
        .firstOrNull { supportedKind(it) != null }

    /** Identifies only relocations whose receiver/qualification change is safe in standalone K2. */
    private fun supportedKind(declaration: KtNamedDeclaration): Kind? = when (declaration) {
        is KtClassOrObject -> declaration.takeIf { it.getStrictParentOfType<KtClassOrObject>() != null }
            ?.let { Kind.NESTED_CLASS }
        is KtNamedFunction, is KtProperty -> declaration.takeIf {
            it.getStrictParentOfType<KtObjectDeclaration>()?.isCompanion() == true
        }?.let { Kind.COMPANION_MEMBER }
        else -> null
    }

    /** Locates the named class/object chosen by the NetBeans target-selection controller. */
    private fun resolveTarget(targetFile: KtFile, targetOffset: Int): KtClassOrObject? =
        targetFile.findElementAt(targetOffset)?.getNonStrictParentOfType<KtClassOrObject>()

    /** Checks target kind, same-owner no-op, duplicate declarations, and Kotlin import aliases. */
    private fun validationMessages(declaration: KtNamedDeclaration, target: KtClassOrObject): List<String> {
        val kind = supportedKind(declaration) ?: return listOf("This declaration cannot be moved safely.")
        val sourceOwner = declaration.getStrictParentOfType<KtClassOrObject>()
            ?: return listOf("Move source owner is unavailable.")
        val messages = mutableListOf<String>()
        if (sourceOwner == target) messages += "The target class already owns ${declaration.name}."
        if (kind == Kind.COMPANION_MEMBER && target.companionObjects.singleOrNull() == null) {
            messages += "Target ${target.name ?: "class"} must declare exactly one companion object."
        }
        val destination = targetContainer(declaration, target)
        val name = declaration.name
        if (destination != null && name != null && destination.declarations.filterIsInstance<KtNamedDeclaration>().any { it.name == name }) {
            messages += "Target ${target.name ?: "class"} already contains $name."
        }
        val aliases = KotlinMoveUsageSearchService.getInstance()?.findUsages(declaration).orEmpty()
            .filterIsInstance<KtSimpleNameReference>()
            .any { it.getImportAlias() != null }
        if (aliases) messages += "Kotlin import aliases are not supported by Move Nested Member."
        return messages
    }

    /** Selects the target class body or its companion body to match the declaration's source kind. */
    private fun targetContainer(declaration: KtNamedDeclaration, target: KtClassOrObject): KtClassOrObject? = when (supportedKind(declaration)) {
        Kind.NESTED_CLASS -> target
        Kind.COMPANION_MEMBER -> target.companionObjects.singleOrNull()
        null -> null
    }

    /** Supported source ownership kinds. */
    private enum class Kind { NESTED_CLASS, COMPANION_MEMBER }
}
