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

import com.intellij.openapi.util.Ref
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.changeSignature.CallerUsageInfo
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageProcessor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinMethodDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinParameterInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinTypeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinFunctionCallUsage
import org.jetbrains.kotlin.idea.refactoring.changeSignature.KotlinValVar
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * Headless analysis + execution engine for the **Change Signature** refactoring (E9.8). M1 covered
 * plain function/constructor calls and parameter-name references; M2 adds overrides (via
 * [org.jetbrains.kotlin.idea.searching.inheritors.findAllOverridings], project-wide) and callable
 * references (`::foo` — already resolved by M1's simple-name-reference scan, since the name part of
 * a callable reference is itself a `KtSimpleNameExpression`) and wires up conflict detection.
 * Constructor delegation, destructuring, enum entries, and by-convention calls follow in M3 — see
 * `docs/development-plan.md`'s E9.8 entry.
 *
 * Drives IDEA's real ported Change Signature engine directly:
 * [KotlinMethodDescriptor]/[KotlinChangeInfo] hold the signature, and
 * [KotlinChangeSignatureUsageProcessor] performs usage search and the actual PSI rewrite. Following
 * the same convention as every other E9.x refactoring, `ChangeSignatureProcessorBase.run()` (IDEA's
 * modal pipeline) is never invoked — [apply] replicates its three orchestration steps directly
 * (preprocess pass, primary-method rewrite, main pass; see
 * `platform/lang-impl/.../ChangeSignatureProcessorBase.java` `performRefactoring()` for the order
 * this mirrors) and conflicts are surfaced to the caller (the NetBeans `RefactoringPlugin`/
 * `RefactoringUI`) instead of showing IDEA's `ConflictsDialog`: [apply] runs
 * [KotlinChangeSignatureUsageProcessor.findConflicts] right after usage search and, if any are
 * found, returns [ApplyOutcome.Conflicts] without mutating anything (hierarchy-dependent checks
 * that would need a whole-project inheritor index return no conflicts standalone, same accepted
 * limitation as Move Declaration).
 *
 * **Why [compute]/[apply] never expose [KotlinChangeInfo]/[KotlinParameterInfo] directly:** those
 * classes implement Java interfaces (`com.intellij.refactoring.changeSignature.ChangeInfo`/
 * `ParameterInfo`) copied from `platform/refactoring/src` but deliberately never javac-compiled in
 * this module (see the `pom.xml` `default-compile` comment: kotlinc resolves them loosely from
 * source, sufficient within this module, but no `.class` bytecode for them ships in this module's
 * jar). A downstream module compiling against the jar (`Nbm`, including its tests) cannot link
 * against a type whose declared supertype has no `.class` file, so the public API here is
 * restricted to plain data ([KaChangeSignatureResult]/[KaChangeSignatureParameter]) — the same
 * pattern [KaMoveDeclarationComputer] already uses (plain `String`/`TextRange` fields, never a raw
 * `K2Move*Descriptor`).
 *
 * @param ktFile      the source file
 * @param caretOffset caret position within the file
 */
class KaChangeSignatureComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
) {
    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not on a function, constructor, or class with a primary constructor. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] is the current signature for the caller to display/edit. */
        data class Ready(val result: KaChangeSignatureResult) : Outcome()
    }

    /** Outcome of actually applying the signature change. */
    sealed class ApplyOutcome {
        /** Conflicts were found; [messages] are human-readable descriptions. Nothing was mutated. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /**
         * The change completed successfully (in-memory PSI only). [fileTexts] maps each touched
         * file's absolute path to its resulting text; the caller must persist each one itself
         * (mirroring how [KaMoveDeclarationComputer.MoveOutcome.Success] hands back per-file text
         * for the caller's `NbDocument`/`FileObject` writes) — generalized here from 1-2 files to N.
         */
        data class Success(val fileTexts: Map<String, String>) : ApplyOutcome()

        /** Applying the change failed with [error]. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    fun compute(): Outcome = try {
        val declaration = findDeclaration()
        if (declaration == null) {
            Outcome.NotApplicable
        } else {
            val methodDescriptor = KotlinMethodDescriptor(declaration)
            Outcome.Ready(
                KaChangeSignatureResult(
                    declarationName = methodDescriptor.name,
                    returnTypeText = methodDescriptor.oldReturnType,
                    parameters = methodDescriptor.parameters.mapIndexed { index, p ->
                        KaChangeSignatureParameter(originalIndex = index, name = p.name, typeText = p.typeText)
                    },
                )
            )
        }
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    /**
     * Finds the function, constructor, or (for a primary constructor) class the caret refers to —
     * either directly (caret inside the declaration itself) or via a reference (caret on a call
     * site / any other usage, anywhere in the project — this project's "cursor-on-declaration-or-
     * usage" convention, see `docs/development-plan.md`'s E9 strategy section). Properties are out
     * of scope for E9.8 (functions/constructors only, per the approved plan).
     */
    private fun findDeclaration(): KtNamedDeclaration? {
        val leaf = ktFile.findElementAt(caretOffset) ?: return null

        fun isSupported(declaration: KtNamedDeclaration) =
            declaration is KtNamedFunction || declaration is KtConstructor<*> || declaration is KtClass

        // Caret on a usage: resolve via K2 to the declaring element, in any file.
        val ref = PsiTreeUtil.getParentOfType(leaf, KtNameReferenceExpression::class.java, false)
        if (ref != null) {
            val resolved = runCatching {
                analyze(ref) { ref.mainReference?.resolveToSymbol()?.psi }
            }.getOrNull()
            (resolved as? KtNamedDeclaration)?.takeIf(::isSupported)?.let { return it }
        }

        // Caret directly on/inside the declaration (its keyword, name, parameter list, or body).
        return leaf.parentsWithSelf
            .filterIsInstance<KtNamedDeclaration>()
            .firstOrNull(::isSupported)
    }

    /**
     * Applies [request] to the declaration at the caret and every one of its usages. Re-resolves
     * the declaration fresh (rather than reusing anything from [compute]) in case PSI has changed
     * since — same reasoning as [KaMoveDeclarationComputer.move] re-resolving instead of caching.
     *
     * Builds a fresh internal [KotlinChangeInfo] from [request] (existing parameters are matched
     * back to the original [KotlinMethodDescriptor.getParameters] by [KaChangeSignatureParameter.originalIndex]
     * to preserve default-value/context-parameter metadata; [KaChangeSignatureParameter.originalIndex]
     * `== -1` means a brand-new parameter with no default value), then mirrors
     * `ChangeSignatureProcessorBase.performRefactoring()`'s three-step order: a
     * `beforeMethodChange=true` pass over every usage, then
     * [KotlinChangeSignatureUsageProcessor.processPrimaryMethod], then a `beforeMethodChange=false`
     * pass, with a conflict check (see class doc) run between usage search and the two usage passes.
     */
    fun apply(request: KaChangeSignatureRequest): ApplyOutcome = try {
        val declaration = findDeclaration()
        if (declaration == null) {
            ApplyOutcome.Error(IllegalStateException("Caret is no longer on a function, constructor, or class"))
        } else {
            val methodDescriptor = KotlinMethodDescriptor(declaration)
            val changeInfo = KotlinChangeInfo(methodDescriptor)
            changeInfo.setNewName(request.newName)
            changeInfo.setType(request.newReturnTypeText)
            changeInfo.clearParameters()
            // Guard against a brand-new parameter (originalIndex == -1, e.g. added via the dialog's
            // "Add" button) whose name collides with a parameter the declaration already has (e.g.
            // the user re-opens the dialog after a previous invocation already added it, or presses
            // "Add" twice with the same name by mistake) — the dialog has no live duplicate-name
            // validation, so silently drop the redundant new parameter here rather than emitting a
            // second same-named parameter on the declaration and its call sites.
            val existingNames = request.parameters.filter { it.originalIndex >= 0 }.map { it.name }.toSet()
            val seenNewNames = mutableSetOf<String>()
            val deduplicatedParameters = request.parameters.filter { p ->
                p.originalIndex >= 0 || (p.name !in existingNames && seenNewNames.add(p.name))
            }
            for (p in deduplicatedParameters) {
                val parameterInfo = if (p.originalIndex >= 0) {
                    methodDescriptor.parameters[p.originalIndex].also {
                        it.setName(p.name)
                        it.setType(p.typeText)
                    }
                } else {
                    KotlinParameterInfo(
                        originalIndex = -1,
                        originalType = KotlinTypeInfo(p.typeText, declaration),
                        name = p.name,
                        valOrVar = KotlinValVar.None,
                        defaultValueForCall = null,
                        defaultValueAsDefaultParameter = false,
                        defaultValue = null,
                        context = declaration,
                    )
                }
                changeInfo.addParameter(parameterInfo, -1)
            }

            applyChangeInfo(changeInfo)
        }
    } catch (e: Exception) {
        ApplyOutcome.Error(e)
    }

    private fun applyChangeInfo(changeInfo: KotlinChangeInfo): ApplyOutcome {
        val processor = KotlinChangeSignatureUsageProcessor()
        val usages = withCallerPropagation(changeInfo, processor.findUsages(changeInfo))

        val conflicts = processor.findConflicts(changeInfo, Ref(usages))
        if (!conflicts.isEmpty) {
            return ApplyOutcome.Conflicts(conflicts.values().toList())
        }

        for (usage in usages) {
            processor.processUsage(changeInfo, usage, beforeMethodChange = true, usages)
        }
        processor.processPrimaryMethod(changeInfo)
        for (usage in usages) {
            processor.processUsage(changeInfo, usage, beforeMethodChange = false, usages)
        }

        val touchedFiles = mutableMapOf<String, KtFile>()
        (changeInfo.method.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        for (usage in usages) {
            (usage.element?.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        }

        return ApplyOutcome.Success(touchedFiles.mapValues { (_, file) -> file.text })
    }

    /**
     * "Propagate to callers": when [changeInfo] adds a parameter with no default value, every
     * direct caller of the changed declaration (the enclosing function of each call-site usage)
     * must itself grow the same parameter, forwarding it down to the original call site — real
     * IDEA's [CallerUsageInfo] mechanism (see [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinFunctionCallUsage.isCaller]),
     * which this project's ported [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageProcessor.findUsages]
     * never constructs on its own (real IDEA only builds it from the excluded, IDE-only
     * "propagate to callers" dialog step) — always propagates to every direct caller found,
     * since this plugin has no UI to let the user pick a subset.
     *
     * [CallerUsageInfo] wraps the caller's light Java method (`toLightMethods()`); processing it
     * later resolves back to the real Kotlin declaration via `PsiElement.unwrapped`, same as every
     * other cross-language bridge already used in this ported engine.
     *
     * Every direct caller is included here, even one that (from an earlier propagation, or a
     * repeated invocation) already declares a same-named parameter — [CallerUsageInfo] is also
     * what [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinFunctionCallUsage.isCaller]
     * consults to decide whether *that caller's own* call site should forward the new argument, so
     * excluding such a caller here would silently stop it from forwarding the value too. The
     * duplicate-declaration guard instead lives at the point the new parameter text is actually
     * appended to the caller's own parameter list (patched into the ported
     * `processParameterListWithStructuralChanges`'s `isCaller` branch), which only needs to skip
     * names already present, not drop the caller from propagation entirely.
     */
    private fun withCallerPropagation(changeInfo: KotlinChangeInfo, usages: Array<UsageInfo>): Array<UsageInfo> {
        val newParameterNames = changeInfo.newParameters.filter { it.isNewParameter }.map { it.name }.toSet()
        if (newParameterNames.isEmpty()) return usages

        val declaration = changeInfo.method
        val callers = usages
            .filterIsInstance<KotlinFunctionCallUsage>()
            .mapNotNull { usage -> usage.element?.let { PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java) } }
            .filter { it != declaration }
            .distinct()

        val callerUsages = callers.mapNotNull { caller ->
            caller.toLightMethods().firstOrNull()?.let { CallerUsageInfo(it, true, false) }
        }
        if (callerUsages.isEmpty()) return usages

        return usages + callerUsages
    }

    private fun KtFile.pathOrName(): String = virtualFile?.path ?: name
}

/**
 * Plain-data snapshot of a declaration's current signature — safe to reference from any module
 * (unlike [KotlinChangeInfo]/[KotlinParameterInfo], see [KaChangeSignatureComputer]'s class doc).
 */
data class KaChangeSignatureResult(
    val declarationName: String,
    val returnTypeText: String,
    val parameters: List<KaChangeSignatureParameter>,
)

/**
 * One parameter, either matched back to an existing one via [originalIndex] (into the
 * [KaChangeSignatureResult] the caller started editing from) or brand-new ([originalIndex] `== -1`).
 */
data class KaChangeSignatureParameter(
    val originalIndex: Int,
    val name: String,
    val typeText: String,
)

/** The caller's requested edits, passed to [KaChangeSignatureComputer.apply]. */
data class KaChangeSignatureRequest(
    val newName: String,
    val newReturnTypeText: String,
    val parameters: List<KaChangeSignatureParameter>,
)
