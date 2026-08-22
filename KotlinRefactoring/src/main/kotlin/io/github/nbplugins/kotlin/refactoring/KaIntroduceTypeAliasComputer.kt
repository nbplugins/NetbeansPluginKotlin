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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggester
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Headless analysis engine for the Kotlin **Introduce Type Alias** refactoring.
 *
 * The generic extraction model follows IDEA K2's `IntroduceTypeAliasData` / descriptor
 * representation, but this standalone computer produces the small document edit plan NetBeans
 * needs. IDEA's action handler, PSI mutation pipeline, and project-index duplicate search remain
 * deliberately outside this module.
 *
 * @param ktFile the file being refactored
 * @param caretOffset caret position within [ktFile]
 * @param selectionRange non-empty editor selection, when invocation was selection-based
 */
class KaIntroduceTypeAliasComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
    private val selectionRange: TextRange? = null,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not on a type reference. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaIntroduceTypeAliasResult) : Outcome()
    }

    /** Runs the analysis and returns an [Outcome]. */
    fun compute(): Outcome = try {
        computeInternal()
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    /** Builds a standalone generic type-alias edit plan from the selected type reference. */
    private fun computeInternal(): Outcome {
        val leaf = ktFile.findElementAt(caretOffset) ?: return Outcome.NotApplicable
        val typeRef = PsiTreeUtil.getParentOfType(leaf, KtTypeReference::class.java, false)
            ?: return Outcome.NotApplicable
        val typeElement = typeRef.typeElement ?: return Outcome.NotApplicable
        val typeText = typeRef.text.trim()
        if (typeText.isEmpty()) return Outcome.NotApplicable
        val extractionMode = extractionMode(typeRef) ?: return Outcome.NotApplicable
        analyze(ktFile) { validateAliasContents(typeRef) }

        val genericShape = when (extractionMode) {
            ExtractionMode.CONCRETE_TYPE -> null
            ExtractionMode.TYPE_CONSTRUCTOR -> analyze(ktFile) { genericShape(typeRef) }
        }
        val suggestedName = KotlinNameSuggester.suggestTypeAliasNameByPsi(typeElement) { true }
            .ifBlank { deriveSimpleName(typeText) }
        val occurrenceReplacements = analyze(ktFile) {
            collectOccurrenceReplacements(typeRef, extractionMode, genericShape, suggestedName)
        }

        return Outcome.Ready(
            KaIntroduceTypeAliasResult(
                typeRefRange = typeRef.textRange,
                typeText = typeText,
                aliasTypeText = genericShape?.aliasTypeText ?: typeText,
                typeParameterNames = genericShape?.parameterNames ?: emptyList(),
                suggestedName = suggestedName,
                occurrenceReplacements = occurrenceReplacements,
                insertOffset = computeInsertOffset(typeRef),
                availableVisibilities = listOf("public", "internal", "private"),
            )
        )
    }

    /**
     * Selects concrete extraction by default and constructor extraction only for an exact constructor
     * selection. A caret has no selection and therefore always aliases the complete type reference.
     */
    private fun extractionMode(typeRef: KtTypeReference): ExtractionMode? {
        val selection = selectionRange ?: return ExtractionMode.CONCRETE_TYPE
        if (selection == typeRef.textRange) return ExtractionMode.CONCRETE_TYPE
        val rootUserType = typeRef.typeElement as? KtUserType ?: return null
        val constructorRange = rootUserType.referenceExpression?.textRange ?: return null
        return ExtractionMode.TYPE_CONSTRUCTOR.takeIf { selection == constructorRange }
    }

    /** The selected PSI shape determines whether arguments remain concrete or become parameters. */
    private enum class ExtractionMode {
        CONCRETE_TYPE,
        TYPE_CONSTRUCTOR,
    }

    /** Validates that every user type within [typeRef] remains accessible to a top-level alias. */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun validateAliasContents(typeRef: KtTypeReference) {
        val userTypes = buildList {
            (typeRef.typeElement as? KtUserType)?.let(::add)
            addAll(typeRef.collectDescendantsOfType<KtUserType>())
        }
        userTypes.forEach { validateAliasScope(it) }
        if (typeRef.containsStarProjection()) {
            throw UnsupportedTypeAlias("Introduce Type Alias does not support star projections")
        }
    }

    /**
     * Creates a recursive generic skeleton for [typeRef].
     *
     * A nested generic argument keeps its constructor in the alias body and recursively abstracts
     * its concrete leaves. Thus `List<Map<String, Int>>` becomes `List<Map<T, U>>`, rather than
     * losing the nested `Map` structure. Each referenced user-type segment is resolved through K2
     * before it becomes part of the plan, preventing a local type parameter from leaking into a
     * top-level alias declaration.
     */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun genericShape(typeRef: KtTypeReference): GenericShape? {
        val root = typeRef.typeElement as? KtUserType ?: return null
        if (!root.hasTypeArgumentsRecursively()) return null

        val parameterNames = mutableListOf<String>()
        val aliasTypeText = renderAliasType(root, parameterNames)
        return GenericShape(parameterNames, aliasTypeText)
    }

    /** Renders one user-type path and replaces its non-generic argument leaves with parameters. */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun renderAliasType(userType: KtUserType, parameterNames: MutableList<String>): String {
        val qualifier = userType.qualifier?.let { "${renderAliasType(it, parameterNames)}." } ?: ""
        val name = userType.referenceExpression?.text
            ?: throw UnsupportedTypeAlias("The selected type has no resolvable constructor")
        validateAliasScope(userType)
        val arguments = userType.typeArguments
        if (arguments.isEmpty()) return "$qualifier$name"

        return buildString {
            append(qualifier)
            append(name)
            append('<')
            append(arguments.joinToString(", ") { projection ->
                val argument = projection.typeReference
                    ?: throw UnsupportedTypeAlias("Introduce Type Alias does not support star projections")
                val argumentUserType = argument.typeElement as? KtUserType
                if (argumentUserType != null && argumentUserType.hasTypeArgumentsRecursively()) {
                    renderAliasType(argumentUserType, parameterNames)
                } else {
                    validateAliasScope(argumentUserType)
                    parameterNames += nextTypeParameterName(parameterNames.size)
                    parameterNames.last()
                }
            })
            append('>')
        }
    }

    /** Rejects symbols whose spelling would be unavailable to the top-level alias declaration. */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun validateAliasScope(userType: KtUserType?) {
        val reference = userType?.referenceExpression ?: return
        when (val symbol = reference.mainReference.resolveToSymbol()) {
            is KaTypeParameterSymbol -> throw UnsupportedTypeAlias(
                "Introduce Type Alias cannot refer to local type parameter '${symbol.name.asString()}'",
            )
            else -> {
                val declaration = symbol?.psi
                if (declaration?.parentsWithSelf?.any { it is KtNamedFunction } == true) {
                    throw UnsupportedTypeAlias(
                        "Introduce Type Alias cannot refer to a type declared in a local function",
                    )
                }
            }
        }
    }

    /** Collects replacements for exact types or K2-resolved generic constructor skeletons in this file. */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun collectOccurrenceReplacements(
        origin: KtTypeReference,
        extractionMode: ExtractionMode,
        genericShape: GenericShape?,
        suggestedName: String,
    ): List<KaIntroduceTypeAliasOccurrence> {
        val occurrences = mutableListOf<KaIntroduceTypeAliasOccurrence>()
        ktFile.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitTypeReference(typeReference: KtTypeReference) {
                val replacementText = replacementText(
                    typeReference,
                    origin,
                    extractionMode,
                    genericShape,
                    suggestedName,
                )
                if (replacementText != null) {
                    occurrences += KaIntroduceTypeAliasOccurrence(typeReference.textRange, replacementText)
                }
                super.visitTypeReference(typeReference)
            }
        })
        return occurrences.sortedBy { it.range.startOffset }
    }

    /** Returns the alias spelling for one compatible occurrence, or `null` when it is unrelated. */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun replacementText(
        candidate: KtTypeReference,
        origin: KtTypeReference,
        extractionMode: ExtractionMode,
        genericShape: GenericShape?,
        suggestedName: String,
    ): String? {
        return when (extractionMode) {
            ExtractionMode.CONCRETE_TYPE -> suggestedName.takeIf { candidate.text.trim() == origin.text.trim() }
            ExtractionMode.TYPE_CONSTRUCTOR -> {
                val shape = genericShape ?: return null
                val originUserType = origin.typeElement as? KtUserType ?: return null
                val candidateUserType = candidate.typeElement as? KtUserType ?: return null
                val arguments = collectSubstitutionArguments(originUserType, candidateUserType) ?: return null
                if (arguments.size != shape.parameterNames.size) return null
                "$suggestedName<${arguments.joinToString(", ")}>"
            }
        }
    }

    /**
     * Matches the resolved constructor skeleton and returns concrete replacements for its leaves.
     *
     * K2 symbol identity is used for every qualified segment, avoiding raw-text collisions between
     * unrelated types with the same short name. A non-generic origin leaf deliberately accepts any
     * well-formed candidate type because it is represented by an alias type parameter.
     */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun collectSubstitutionArguments(origin: KtUserType, candidate: KtUserType): List<String>? {
        if (!sameResolvedConstructor(origin, candidate)) return null
        val values = mutableListOf<String>()
        val originQualifier = origin.qualifier
        val candidateQualifier = candidate.qualifier
        if (originQualifier != null && candidateQualifier != null) {
            val qualifierValues = collectSubstitutionArguments(originQualifier, candidateQualifier) ?: return null
            values += qualifierValues
        }
        for ((originProjection, candidateProjection) in origin.typeArguments.zip(candidate.typeArguments)) {
            val originArgument = originProjection.typeReference ?: return null
            val candidateArgument = candidateProjection.typeReference ?: return null
            if (originArgument.containsStarProjection() || candidateArgument.containsStarProjection()) return null

            val originNested = originArgument.typeElement as? KtUserType
            val candidateNested = candidateArgument.typeElement as? KtUserType
            if (originNested != null && originNested.hasTypeArgumentsRecursively()) {
                if (candidateNested == null) return null
                val nestedValues = collectSubstitutionArguments(originNested, candidateNested) ?: return null
                values += nestedValues
            } else {
                values += candidateArgument.text.trim()
            }
        }
        return values
    }

    /** Checks that both qualified paths resolve to the same K2 symbols and have matching arity. */
    context(_: org.jetbrains.kotlin.analysis.api.KaSession)
    private fun sameResolvedConstructor(origin: KtUserType, candidate: KtUserType): Boolean {
        if (origin.typeArguments.size != candidate.typeArguments.size) return false
        val originReference = origin.referenceExpression ?: return false
        val candidateReference = candidate.referenceExpression ?: return false
        val originSymbol = originReference.mainReference.resolveToSymbol()
        val candidateSymbol = candidateReference.mainReference.resolveToSymbol()
        if (originSymbol != null && candidateSymbol != null && originSymbol != candidateSymbol) return false
        if ((originSymbol == null) != (candidateSymbol == null) ||
            (originSymbol == null && originReference.text != candidateReference.text)
        ) return false

        val originQualifier = origin.qualifier
        val candidateQualifier = candidate.qualifier
        return when {
            originQualifier == null && candidateQualifier == null -> true
            originQualifier != null && candidateQualifier != null -> sameResolvedConstructor(originQualifier, candidateQualifier)
            else -> false
        }
    }

    /** Returns whether this user-type path owns generic arguments at any level. */
    private fun KtUserType.hasTypeArgumentsRecursively(): Boolean =
        typeArguments.isNotEmpty() || qualifier?.hasTypeArgumentsRecursively() == true

    /** Returns whether [this] contains a star projection at any nesting level. */
    private fun KtTypeReference.containsStarProjection(): Boolean =
        collectDescendantsOfType<KtTypeProjection>().any { it.typeReference == null }

    /** Finds the declaration insertion point before the enclosing top-level declaration. */
    private fun computeInsertOffset(typeRef: KtTypeReference): Int {
        val topLevelDecl = typeRef.parentsWithSelf.firstOrNull { it.parent is KtFile }
        return topLevelDecl?.startOffset ?: (ktFile.importList?.textRange?.endOffset?.plus(1) ?: 0)
    }

    /** Derives a fallback PascalCase alias name from a type spelling. */
    private fun deriveSimpleName(typeText: String): String {
        val base = typeText.substringBefore('<').substringAfterLast('.').trim()
        return base.replaceFirstChar { it.uppercaseChar() }.ifBlank { "MyAlias" }
    }

    /** Uses conventional sequential Kotlin generic parameter names. */
    private fun nextTypeParameterName(index: Int): String {
        val bases = listOf("T", "U", "V", "W", "X", "Y", "Z")
        val base = bases[index % bases.size]
        val suffix = index / bases.size
        return if (suffix == 0) base else "$base$suffix"
    }

    /** Generic skeleton and its ordered type-parameter names. */
    private data class GenericShape(
        val parameterNames: List<String>,
        val aliasTypeText: String,
    )

    /** Indicates a selected type cannot safely be represented by a top-level typealias. */
    private class UnsupportedTypeAlias(message: String) : IllegalArgumentException(message)
}

/** A source range and its alias replacement spelling. */
data class KaIntroduceTypeAliasOccurrence(
    val range: TextRange,
    val replacementText: String,
)

/** All data needed by NetBeans to introduce and apply a type alias. */
data class KaIntroduceTypeAliasResult(
    val typeRefRange: TextRange,
    val typeText: String,
    val aliasTypeText: String,
    val typeParameterNames: List<String>,
    val suggestedName: String,
    val occurrenceReplacements: List<KaIntroduceTypeAliasOccurrence>,
    val insertOffset: Int,
    val availableVisibilities: List<String>,
) {
    /** Legacy preview ranges retained for the NetBeans occurrence-element bridge. */
    val occurrenceRanges: List<TextRange>
        get() = occurrenceReplacements.map { it.range }

    /** Renders the typealias declaration body using [name]. */
    fun renderAliasDeclaration(name: String, visibilityPrefix: String): String {
        val parameters = typeParameterNames.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "<", postfix = ">")
            ?: ""
        return "${visibilityPrefix}typealias $name$parameters = $aliasTypeText"
    }

    /** Replaces this result's suggested alias name in a precomputed occurrence replacement. */
    fun replacementFor(occurrence: KaIntroduceTypeAliasOccurrence, chosenName: String): String =
        occurrence.replacementText.replaceFirst(suggestedName, chosenName)
}
