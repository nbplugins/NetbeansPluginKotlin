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

import com.intellij.refactoring.util.DocCommentPolicy
import org.jetbrains.kotlin.idea.k2.refactoring.extractClass.K2ExtractSuperRefactoring
import org.jetbrains.kotlin.idea.refactoring.introduce.extractClass.ExtractSuperInfo
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType

/** Identifies which real IDEA Extract Super mode an [KaExtractSuperComputer] request uses. */
enum class ExtractSuperKind {
    /** Creates an interface and makes the source class implement it. */
    INTERFACE,
    /** Creates a superclass and makes the source class extend it. */
    SUPERCLASS,
}

/** A member offered by IDEA's Extract Super engine to the NetBeans dialog. */
data class ExtractSuperMemberCandidate(
    /** Stable source offset used to resolve the declaration afresh before applying. */
    val offset: Int,
    /** IDEA K2-rendered member label. */
    val presentation: String,
    /** Whether IDEA allows moving this member. */
    val enabled: Boolean,
    /** Whether IDEA initially makes this member abstract. */
    val makeAbstract: Boolean,
)

/** User choices consumed by [KaExtractSuperComputer.apply]. */
data class ExtractSuperRequest(
    /** Start offset of the class selected during pre-refactoring discovery. */
    val classOffset: Int,
    /** Requested extracted type name. */
    val name: String,
    /** Requested extraction target kind. */
    val kind: ExtractSuperKind,
    /** Offsets of selected members in the original class. */
    val selectedOffsets: Set<Int>,
    /** Offsets whose moved members should become abstract. */
    val abstractOffsets: Set<Int>,
    /** Name of the already-created Kotlin file that receives the extracted type. */
    val targetFileName: String,
    /** Package directive for the target Kotlin file, empty for the default package. */
    val targetPackage: String,
)

/**
 * Headless adapter to IntelliJ IDEA's real K2 Extract Super refactoring engine.
 *
 * This class only resolves the source class and selected IDEA [KotlinMemberInfo] instances. All
 * semantic mutation, type-parameter collection, inheritance changes, and member migration are
 * performed by [K2ExtractSuperRefactoring] and its K2 Pull Up helper.
 *
 * @param file Kotlin file containing the invocation caret.
 * @param caretOffset invocation caret offset.
 */
class KaExtractSuperComputer(
    private val file: KtFile,
    private val caretOffset: Int,
) {
    /** Result of source-class/member discovery. */
    sealed interface Discovery {
        /** No extractable Kotlin class/object exists at the caret. */
        data object NotApplicable : Discovery
        /** IDEA candidates ready for dialog display. */
        data class Ready(
            /** Start offset of the source class used to refresh lookup before apply. */
            val classOffset: Int,
            /** Source class name used by the UI. */
            val sourceName: String,
            /** Members discovered by IDEA's member-info implementation. */
            val members: List<ExtractSuperMemberCandidate>,
        ) : Discovery
        /** Unexpected K2/PSI failure. */
        data class Error(val error: Throwable) : Discovery
    }

    /** Result of applying an extraction request. */
    sealed interface Apply {
        /** Request cannot be resolved against the current PSI. */
        data object NotApplicable : Apply
        /** The engine completed; both session PSI files contain their post-refactoring text. */
        data class Success(
            /** Mutated source file text. */
            val sourceText: String,
            /** Mutated extracted-type file text. */
            val targetText: String,
        ) : Apply
        /** IDEA engine rejected the request or mutation failed. */
        data class Error(val error: Throwable) : Apply
    }

    /**
     * Resolves the enclosing class and converts real IDEA member information into UI data.
     *
     * @return candidate data or a non-mutating outcome.
     */
    fun discover(): Discovery = runCatching {
        val klass = findClass() ?: return Discovery.NotApplicable
        val members = klass.declarations.filterIsInstance<KtNamedDeclaration>().map { declaration ->
            val info = KotlinMemberInfo(declaration)
            ExtractSuperMemberCandidate(
                offset = declaration.textOffset,
                presentation = info.displayName,
                enabled = true,
                makeAbstract = false,
            )
        }
        Discovery.Ready(klass.textOffset, klass.name ?: "ExtractedType", members)
    }.getOrElse(Discovery::Error)

    /**
     * Runs IDEA's K2 engine in the current writable session PSI.
     *
     * The NetBeans adapter creates [targetFile] through the regular filesystem, then refreshes the
     * K2 session before invoking this method, so IDEA receives both writable PSI files.
     *
     * @param request validated NetBeans dialog request.
     * @param targetFile writable, already-created destination Kotlin PSI file.
     * @return post-mutation text or an error outcome.
     */
    fun apply(request: ExtractSuperRequest, targetFile: KtFile): Apply = runCatching {
        val klass = file.findElementAt(request.classOffset)?.getNonStrictParentOfType<KtClassOrObject>()
            ?: findClass()
            ?: request.selectedOffsets.asSequence()
                .mapNotNull { offset -> file.findElementAt(offset)?.getNonStrictParentOfType<KtClassOrObject>() }
                .firstOrNull()
            ?: return Apply.NotApplicable
        val selected = klass.declarations.filterIsInstance<KtNamedDeclaration>()
            .filter { it.textOffset in request.selectedOffsets }
            .map { declaration ->
                KotlinMemberInfo(declaration).apply {
                    isChecked = true
                    isToAbstract = declaration.textOffset in request.abstractOffsets
                }
            }
        if (request.name.isBlank() || request.targetFileName.isBlank() || selected.isEmpty()) return Apply.NotApplicable

        K2ExtractSuperRefactoring().performRefactoring(
            ExtractSuperInfo(
                originalClass = klass,
                memberInfos = selected,
                targetParent = targetFile.parent ?: return Apply.NotApplicable,
                targetFileName = request.targetFileName,
                newClassName = request.name,
                isInterface = request.kind == ExtractSuperKind.INTERFACE,
                docPolicy = DocCommentPolicy(0),
            ),
        )
        Apply.Success(file.text, targetFile.text)
    }.getOrElse(Apply::Error)

    /** @return the class or object under the original caret, if any. */
    private fun findClass(): KtClassOrObject? =
        file.findElementAt(caretOffset)?.getNonStrictParentOfType<KtClassOrObject>()
}
