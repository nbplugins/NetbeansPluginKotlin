// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * Adapter that runs a [KotlinPsiUpdateModCommandAction.ElementBased] IDEA fix
 * in the NetBeans context by:
 * 1. Constructing an [ActionContext] from the diagnostic location.
 * 2. Calling the fix's [invoke][KotlinPsiUpdateModCommandAction.invoke] directly
 *    (bypassing the deferred [ModCommand] machinery).
 * 3. Reading the mutated PSI text back from [kaKtFile] and writing it to [doc].
 *
 * The fix's `invoke()` method uses [NoOpModPsiUpdater] whose [NoOpModPsiUpdater.getWritable]
 * returns elements unchanged, so PSI mutations are applied directly to the K2 session tree.
 *
 * @param kaError the K2 diagnostic that triggered this fix
 * @param ideaFix the IDEA fix action to delegate to
 * @param element the target PSI element (must belong to [kaKtFile])
 * @param elementContext the fix's context object (stored in [KotlinPsiUpdateModCommandAction.ElementBased])
 * @param doc the Swing document for the file
 * @param kaKtFile K2-session-owned [KtFile] whose AST ([KtFile.node].text) is read after PSI mutation
 * @param description user-visible fix label
 */
class KaModCommandFix<E : PsiElement, C : Any>(
    private val kaError: KaDiagnosticError,
    private val ideaFix: KotlinPsiUpdateModCommandAction.ElementBased<E, C>,
    private val element: E,
    private val elementContext: C,
    private val doc: Document,
    private val kaKtFile: KtFile,
    private val description: String,
) : KaQuickFix {

    override fun isApplicable(): Boolean = true

    override fun getDescription(): String = description

    override fun createHint(): Hint = createHintWith(this)

    override fun createHintWith(fix: HintFix): Hint = Hint(
        KotlinRule(HintSeverity.ERROR),
        description,
        kaError.file,
        OffsetRange(kaError.startPosition, kaError.endPosition),
        listOf(fix),
        Priorities.HINT_PRIORITY
    )

    /**
     * Invokes the IDEA fix and syncs the mutated PSI text to the Swing document.
     *
     * [kaKtFile]`.text` reads from the VirtualFile (unchanged after PSI mutation).
     * [kaKtFile]`.node.text` reads from the in-memory AST, which IS updated by
     * PSI mutation calls in [ideaFix]'s [invoke][KotlinPsiUpdateModCommandAction.invoke] body.
     */
    override fun implement() {
        val textBefore = kaKtFile.node.text
        val ctx = ActionContext(
            element.project,
            kaKtFile,
            kaError.startPosition,
            TextRange.EMPTY_RANGE,
            element
        )
        ideaFix.invoke(ctx, element, elementContext, NoOpModPsiUpdater)
        val newText = textWithSeparatedTokens(kaKtFile)
        if (newText == textBefore) return
        val prefix = commonPrefixLength(textBefore, newText)
        val suffix = commonSuffixLength(textBefore, newText, prefix)
        val removeLen = textBefore.length - prefix - suffix
        val insertText = newText.substring(prefix, newText.length - suffix)
        doc.atomicChange {
            if (removeLen > 0) remove(prefix, removeLen)
            if (insertText.isNotEmpty()) insertString(prefix, insertText, null)
        }
    }

    /**
     * Walks all PSI leaves of [file] in document order and reconstructs the file text,
     * inserting a single space between any two adjacent non-whitespace leaves whose
     * concatenation would re-lex as a different token sequence.
     *
     * IDEA fix actions insert new modifier/keyword/identifier leaves directly into the AST
     * without surrounding [com.intellij.psi.PsiWhiteSpace] nodes. The real IDEA flow relies
     * on [com.intellij.psi.codeStyle.CodeStyleManager.reformat] to add whitespace per code style,
     * but in this standalone container that service is a no-op stub. Without normalization
     * the document text would contain glued tokens like `valname`, `noinlineaction`,
     * `@JvmInlinevalue` — none of which re-parse to the intended AST.
     *
     * Heuristic: if the previous leaf ends with a word character (letter, digit, `_`, `$`)
     * and the next leaf begins with one, the Kotlin lexer would merge them into a single
     * identifier/keyword token. Inserting a single space prevents that.
     *
     * @param file the K2-session-owned [KtFile] whose AST was just mutated
     * @return file text with a single space inserted at every problematic leaf boundary
     */
    private fun textWithSeparatedTokens(file: KtFile): String {
        val sb = StringBuilder()
        var prevLast: Char? = null
        com.intellij.psi.util.PsiTreeUtil.processElements(file) { el ->
            if (el.firstChild == null) {
                val txt = el.text
                if (txt.isNotEmpty()) {
                    val first = txt[0]
                    when {
                        prevLast == null -> { /* first leaf, no separator needed */ }
                        startsNewBlockStatement(el) && !sbEndsWithNewline(sb) ->
                            sb.append('\n')
                        isWordChar(prevLast!!) && isWordChar(first) ->
                            sb.append(' ')
                    }
                    sb.append(txt)
                    prevLast = txt[txt.length - 1]
                }
            }
            true
        }
        return sb.toString()
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun sbEndsWithNewline(sb: StringBuilder): Boolean {
        // Walk back through trailing inline whitespace (' ', '\t') — they don't separate statements.
        var i = sb.length - 1
        while (i >= 0 && (sb[i] == ' ' || sb[i] == '\t')) i--
        return i >= 0 && sb[i] == '\n'
    }

    /**
     * Returns true when [leaf] is the leftmost descendant of a statement child of a
     * [org.jetbrains.kotlin.psi.KtBlockExpression] and is NOT the first such statement.
     * A second or later statement in a Kotlin block must be separated from the previous
     * one by a newline or `;` — without that the parser rejects the source.
     */
    private fun startsNewBlockStatement(leaf: com.intellij.psi.PsiElement): Boolean {
        // Find the immediate ancestor that is a direct child of a KtBlockExpression.
        var node: com.intellij.psi.PsiElement = leaf
        var parent = node.parent
        while (parent != null && parent !is org.jetbrains.kotlin.psi.KtBlockExpression) {
            node = parent
            parent = node.parent
        }
        if (parent !is org.jetbrains.kotlin.psi.KtBlockExpression) return false
        // [leaf] must be the very first leaf of [node].
        var first: com.intellij.psi.PsiElement = node
        while (first.firstChild != null) first = first.firstChild
        if (first !== leaf) return false
        // [node] must have a preceding statement sibling (not LBRACE / whitespace only).
        var prev: com.intellij.psi.PsiElement? = node.prevSibling
        while (prev != null && prev is com.intellij.psi.PsiWhiteSpace) prev = prev.prevSibling
        return prev != null && prev !is com.intellij.psi.PsiErrorElement && prev.text != "{"
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
        val max = minOf(a.length, b.length) - prefix
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }
}
