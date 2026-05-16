@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.fixes

import com.intellij.psi.PsiWhiteSpace
import org.netbeans.modules.csl.api.HintFix
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.reformatting.format
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.utils.ProjectUtils
import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.fixes.ImplementMembersFix].
 *
 * Generates stub implementations for all abstract members that the class does not yet override.
 * Uses K2 [KaClassSymbol.memberScope] to find abstract callables, then renders each with
 * [KaDeclarationRendererForSource.WITH_SHORT_NAMES].
 *
 * @param kaError the K2 diagnostic error (ABSTRACT_MEMBER_NOT_IMPLEMENTED or similar)
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaImplementMembersFix(
    private val kaError: KaDiagnosticError,
    private val kaKtFile: KtFile
) : KaQuickFix {

    companion object {
        private const val ABSTRACT_MEMBER_NOT_IMPLEMENTED = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"
        private const val ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED = "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED"
    }

    override fun isApplicable(): Boolean = when (kaError.key) {
        ABSTRACT_MEMBER_NOT_IMPLEMENTED,
        ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED -> true
        else -> false
    }

    override fun getDescription(): String = "Implement members"

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR),
        getDescription(),
        kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition),
        listOf(fix),
        Priorities.HINT_PRIORITY
    )

    override fun implement() {
        val diagOffset = kaError.startPosition

        val kaClassOrObject: KtClassOrObject = kaKtFile
            .findElementAt(diagOffset)
            ?.let { PsiTreeUtil.getParentOfType(it, KtClassOrObject::class.java, false) }
            ?: run {
                KotlinLogger.INSTANCE.logInfo("KaImplementMembersFix: no KtClassOrObject at offset $diagOffset")
                return
            }

        val doc: Document = ProjectUtils.getDocumentFromFileObject(
            kaKtFile.virtualFile
                ?.let { org.openide.filesystems.FileUtil.toFileObject(java.io.File(it.path)) }
                ?: return
        ) ?: return

        val stubs = analyze(kaKtFile) {
            val classSymbol = kaClassOrObject.classSymbol
            if (classSymbol == null) {
                KotlinLogger.INSTANCE.logInfo("KaImplementMembersFix: classSymbol is null for ${kaClassOrObject.name}")
                return@analyze emptyList<String>()
            }
            val allCallables = classSymbol.memberScope.callables { true }.toList()
            // Names of non-abstract callables already declared directly in this class —
            // these are members already overridden and must not be re-generated.
            val alreadyDeclaredNames = classSymbol.declaredMemberScope
                .callables { true }
                .filter { it.modality != KaSymbolModality.ABSTRACT }
                .mapNotNull { it.callableId?.callableName?.asString() }
                .toSet()
            val abstractCallables = allCallables
                .filter { it.modality == KaSymbolModality.ABSTRACT }
                .filter { it.callableId?.callableName?.asString() !in alreadyDeclaredNames }
            KotlinLogger.INSTANCE.logInfo(
                "KaImplementMembersFix: class=${kaClassOrObject.name}, " +
                "totalCallables=${allCallables.size}, abstract=${abstractCallables.size}, " +
                "alreadyDeclared=$alreadyDeclaredNames"
            )
            abstractCallables.map { generateStub(it) }.toList()
        }

        if (stubs.isEmpty()) {
            KotlinLogger.INSTANCE.logInfo("KaImplementMembersFix: stubs is empty, nothing to insert")
            return
        }

        doc.atomicChange {
            generateMethods(this, kaClassOrObject, stubs)
            format(this, kaClassOrObject.textRange.startOffset)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    context(org.jetbrains.kotlin.analysis.api.KaSession)
    private fun generateStub(symbol: KaCallableSymbol): String {
        val signature = symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES)
            .replace(Regex("\\babstract\\b\\s*"), "")
            .trim()

        val body = when (symbol) {
            is KaNamedFunctionSymbol -> "{ TODO(\"Not yet implemented\") }"
            is KaPropertySymbol -> {
                if (symbol.isVal) "\n    get() = TODO(\"Not yet implemented\")"
                else "\n    get() = TODO(\"Not yet implemented\")\n    set(value) { TODO(\"Not yet implemented\") }"
            }
            else -> "{ TODO(\"Not yet implemented\") }"
        }

        val overridePrefix = if (!signature.startsWith("override")) "override " else ""
        return "$overridePrefix$signature $body"
    }

    private fun generateMethods(document: Document, classOrObject: KtClassOrObject, stubs: List<String>) {
        val body = classOrObject.body
        if (body == null) {
            document.insertString(classOrObject.textRange.endOffset, " {\n${stubs.joinToString("\n")}\n}", null)
        } else {
            removeWhitespaceAfterLBrace(body, document)
            val insertOffset = findLBraceEndOffset(document, classOrObject.textRange.startOffset) ?: return
            document.insertString(insertOffset, "\n${stubs.joinToString("\n")}\n", null)
        }
    }

    private fun removeWhitespaceAfterLBrace(body: KtClassBody, document: Document) {
        val lBrace = body.lBrace ?: return
        val sibling = lBrace.nextSibling
        val needNewLine = sibling?.nextSibling is KtDeclaration
        if (sibling is PsiWhiteSpace && !needNewLine) {
            document.remove(sibling.textRange.startOffset, sibling.textLength)
        }
    }

    private fun findLBraceEndOffset(document: Document, startIndex: Int): Int? {
        val text = document.getText(0, document.length)
        return (startIndex..text.lastIndex).firstOrNull { text[it] == '{' }?.let { it + 1 }
    }
}
