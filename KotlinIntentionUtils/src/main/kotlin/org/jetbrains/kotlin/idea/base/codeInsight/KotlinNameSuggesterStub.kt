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
package org.jetbrains.kotlin.idea.base.codeInsight

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaUsualClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Binary-compatible stub for the full era-253 [KotlinNameSuggester].
 *
 * The full implementation depends on IntelliJ Platform UI APIs (NameUtilCore, PsiUtil, etc.)
 * not available in standalone (NetBeans) mode.  This stub is binary-compatible — it has the
 * same constructor signature (with [Case] and [EscapingRules] inner classes and default
 * arguments) and exposes the same public methods — so that bytecode compiled against the full
 * version can run against this stub without [NoSuchMethodError].
 *
 * Registered at build-time by [KotlinIntentionUtils]; shadowed in test sessions by the same JAR.
 */
class KotlinNameSuggester(
    @Suppress("unused") private val case: Case = Case.CAMEL,
    @Suppress("unused") private val escaping: EscapingRules = EscapingRules.DEFAULT,
    @Suppress("unused") private val ignoreCompanionNames: Boolean = true,
) {
    /**
     * Binary-compatible stub for [KotlinNameSuggester.EscapingRules].
     *
     * [shouldEscape] always returns `false` (no keyword escaping in standalone mode).
     * The constructor signature with its five defaulted parameters must match the full version
     * exactly so that default-argument synthetic constructors are binary-compatible.
     */
    class EscapingRules(
        @Suppress("unused") private val escapeKotlinHardKeywords: Boolean = true,
        @Suppress("unused") private val escapeKotlinSoftKeywords: Boolean = false,
        @Suppress("unused") private val escapeJavaHardKeywords: Boolean = false,
        @Suppress("unused") private val escapeJavaSoftKeywords: Boolean = false,
        private val escaper: (String) -> List<String> = DEFAULT_ESCAPER,
    ) {
        companion object {
            /** Minimal escaper mirroring the full version's defaults for common keywords. */
            val DEFAULT_ESCAPER: (String) -> List<String> = { name: String ->
                when (name) {
                    "class" -> listOf("klass", "clazz")
                    "fun" -> listOf("function", "fn", "func", "f")
                    "null" -> listOf("nothing", "nil")
                    "this" -> listOf("self", "me", "owner")
                    "const" -> listOf("constant", "value")
                    "enum" -> listOf("enumeration")
                    "package" -> listOf("pkg")
                    else -> listOf("`$name`")
                }
            }

            /** Default escaping rules (Kotlin hard keywords only). */
            val DEFAULT = EscapingRules()

            /** No escaping at all. */
            val NONE = EscapingRules(
                escapeKotlinHardKeywords = false,
                escapeKotlinSoftKeywords = false,
                escapeJavaHardKeywords = false,
                escapeJavaSoftKeywords = false,
                escaper = { listOf(it) },
            )
        }

        /** Stub: always returns `false` — no keyword checking in standalone mode. */
        fun shouldEscape(@Suppress("unused") name: String): Boolean = false

        /** Returns escaped variants of [name] using the configured [escaper]. */
        fun escape(name: String): List<String> = escaper(name)
    }

    /** Case transformation — mirrors [KotlinNameSuggester.CaseTransformation] ordinals and names. */
    enum class CaseTransformation(val processor: (String) -> String) {
        DEFAULT({ it }),
        UPPERCASE({ it.uppercase() }),
        LOWERCASE({ it.lowercase() }),
    }

    /** Name style — mirrors [KotlinNameSuggester.Case] ordinals and names. */
    enum class Case(
        val case: CaseTransformation,
        val separator: String?,
        val capitalizeFirst: Boolean,
        val capitalizeNext: Boolean,
    ) {
        PASCAL(CaseTransformation.DEFAULT, null, capitalizeFirst = true, capitalizeNext = true),
        CAMEL(CaseTransformation.DEFAULT, null, capitalizeFirst = false, capitalizeNext = true),
        SNAKE(CaseTransformation.LOWERCASE, "_", capitalizeFirst = false, capitalizeNext = false),
        SCREAMING_SNAKE(CaseTransformation.UPPERCASE, "_", capitalizeFirst = false, capitalizeNext = false),
        KEBAB(CaseTransformation.LOWERCASE, "-", capitalizeFirst = false, capitalizeNext = false),
    }

    /**
     * Suggests parameter names for a given type.
     * Returns a short lowercase name derived from the class name, or "it"/"fn" as fallback.
     *
     * @param type the type for which to suggest names
     * @return a lazy sequence of candidate names, starting with the best suggestion
     */
    context(_: KaSession)
    fun suggestTypeNames(type: KaType): Sequence<String> = sequence {
        val className = when (type) {
            is KaUsualClassType -> type.classId.shortClassName.asString()
                .replaceFirstChar { it.lowercaseChar() }
            is KaFunctionType -> "fn"
            else -> "it"
        }
        yield(className.ifEmpty { "it" })
    }

    /**
     * Suggests variable names for [expression], validating each candidate with [validator].
     *
     * Stub: derives a name from simple name expressions or call expressions; falls back to
     * "value".  The full era-253 implementation uses NameUtilCore word-splitting and type
     * inference — this stub is sufficient for NetBeans Introduce Variable to produce a
     * reasonable default name.
     *
     * @param expression the expression for which to suggest a name
     * @param validator  returns `true` if the candidate name is acceptable (no conflict)
     * @return a sequence of candidate names in preference order
     */
    context(_: KaSession)
    fun suggestExpressionNames(
        expression: KtExpression,
        validator: (String) -> Boolean = { true },
    ): Sequence<String> = sequence {
        val derived: String? = when (expression) {
            is KtSimpleNameExpression ->
                expression.getReferencedName().replaceFirstChar { it.lowercaseChar() }
            is KtCallExpression ->
                expression.calleeExpression?.text?.replaceFirstChar { it.lowercaseChar() }
            else -> null
        }?.takeIf { it.isNotEmpty() }
        if (derived != null && validator(derived)) yield(derived)
        val fallback = "value"
        if (validator(fallback)) yield(fallback) else yield("v")
    }

    companion object {
        /**
         * Suggests distinct conventional names for extracted type parameters.
         *
         * The full IDEA implementation uses richer context-sensitive naming. Standalone
         * refactorings only need stable, valid defaults for the K2 Introduce Type Alias model.
         *
         * @param count number of type parameter names to produce
         * @param validator returns `true` when a candidate is available
         * @return exactly [count] distinct accepted names
         */
        fun suggestNamesForTypeParameters(count: Int, validator: (String) -> Boolean): List<String> {
            val names = ArrayList<String>(count)
            val bases = listOf("T", "U", "V", "W", "X", "Y", "Z")
            var attempt = 0
            while (names.size < count) {
                val base = bases[attempt % bases.size]
                val suffix = attempt / bases.size
                val candidate = if (suffix == 0) base else "$base$suffix"
                attempt++
                if (validator(candidate)) names += candidate
            }
            return names
        }

        /**
         * Suggests a type alias name derived from the PSI structure of [typeElement].
         *
         * Ported verbatim from `KotlinNameSuggester.suggestTypeAliasNameByPsi` in
         * `base/code-insight/src` (era-253).  The stub provides this method so that
         * call sites compiled against the full version can use it in standalone (NetBeans) mode
         * without [NoSuchMethodError].
         *
         * @param typeElement the PSI type element whose shape drives the name
         * @param validator   returns `true` when the candidate name is acceptable (no conflicts)
         * @return a name derived from the type shape, with a numeric suffix when [validator] rejects the base
         */
        fun suggestTypeAliasNameByPsi(typeElement: KtTypeElement, validator: (String) -> Boolean): String {
            fun KtTypeElement.render(): String = when (this) {
                is KtNullableType -> "Nullable${innerType?.render() ?: ""}"
                is KtFunctionType -> {
                    val arguments = listOfNotNull(receiverTypeReference) +
                            parameters.mapNotNull { it.typeReference }
                    val argText = arguments.joinToString("") { it.typeElement?.render() ?: "" }
                    val returnText = returnTypeReference?.typeElement?.render() ?: "Unit"
                    "${argText}To$returnText"
                }
                is KtUserType -> {
                    val argText = typeArguments.joinToString("") { it.typeReference?.typeElement?.render() ?: "" }
                    "$argText${referenceExpression?.text ?: ""}"
                }
                else -> text.replaceFirstChar { it.uppercaseChar() }
            }
            return suggestNameByName(typeElement.render(), validator)
        }

        /**
         * Suggests a name based on [name], ensuring it passes [validator].
         * If [name] is rejected, appends incrementing numeric suffixes until accepted.
         *
         * @param name      the preferred base name
         * @param validator returns true if the candidate name is acceptable
         * @return the first accepted candidate name
         */
        fun suggestNameByName(name: String, validator: (String) -> Boolean): String {
            if (validator(name)) return name
            var index = 0
            while (true) {
                val candidate = "$name${++index}"
                if (validator(candidate)) return candidate
            }
        }
    }
}
