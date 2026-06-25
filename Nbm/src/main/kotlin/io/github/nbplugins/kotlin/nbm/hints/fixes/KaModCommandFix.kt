// Copyright 2000-2024 JetBrains s.r.o.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.hints.fixes

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import javax.swing.text.Document

/**
 * Adapter that runs a [PsiUpdateModCommandAction] IDEA fix in the NetBeans context by:
 * 1. Constructing an [ActionContext] from the diagnostic location.
 * 2. Calling the fix's protected `invoke(ActionContext, E, ModPsiUpdater)` via reflection
 *    (bypassing the deferred [ModCommand] machinery and the access modifier).
 * 3. Reading the mutated PSI text back from [kaKtFile] and writing it to [doc].
 *
 * The fix's `invoke()` receives [NoOpModPsiUpdater] whose [NoOpModPsiUpdater.getWritable]
 * returns elements unchanged, so PSI mutations are applied directly to the K2 session tree.
 *
 * @param kaError the K2 diagnostic that triggered this fix
 * @param ideaFix the IDEA fix action to delegate to
 * @param element the target PSI element (must belong to [kaKtFile])
 * @param doc the Swing document for the file
 * @param kaKtFile K2-session-owned [KtFile] whose AST ([KtFile.node].text) is read after PSI mutation
 * @param description user-visible fix label
 */
class KaModCommandFix<E : PsiElement>(
    private val kaError: KaDiagnosticError,
    private val ideaFix: PsiUpdateModCommandAction<E>,
    private val element: E,
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
     * PSI mutation calls in [ideaFix]'s `invoke` body.
     */
    override fun implement() {
        val ctx = ActionContext(
            element.project,
            kaKtFile,
            kaError.startPosition,
            TextRange.EMPTY_RANGE,
            element
        )
        syncMutation(kaKtFile, doc) { callProtectedInvoke(ctx, NoOpModPsiUpdater) }
    }

    /**
     * Calls `invoke(ActionContext, E, ModPsiUpdater)` on [ideaFix] via reflection.
     *
     * In era 253 the IntelliJ fixes extend [PsiUpdateModCommandAction] whose `invoke` is
     * `protected abstract` — not directly callable from outside the hierarchy. Reflection
     * is required. We walk the class hierarchy looking for the first non-bridge, non-synthetic
     * `invoke` with three parameters; the concrete override in each fix subclass is found first.
     */
    private fun callProtectedInvoke(ctx: ActionContext, updater: ModPsiUpdater) {
        var cls: Class<*>? = ideaFix.javaClass
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull { m ->
                m.name == "invoke" && m.parameterCount == 3 && !m.isSynthetic && !m.isBridge
            }
            if (m != null) {
                m.isAccessible = true
                m.invoke(ideaFix, ctx, element, updater)
                return
            }
            cls = cls.superclass
        }
        error("Cannot find invoke(ActionContext, E, ModPsiUpdater) on ${ideaFix.javaClass}")
    }

    companion object {
        /**
         * Applies [mutate] as a PSI mutation and syncs the result to [doc].
         *
         * Reads [kaKtFile]`.node.text` before and after [mutate], then writes only the
         * changed region to [doc]. Uses [textWithSeparatedTokens] to insert spaces between
         * tokens that IDEA's fix actions glue together without whitespace (the real IDEA flow
         * relies on [com.intellij.psi.codeStyle.CodeStyleManager.reformat], which is a no-op
         * stub in this standalone container).
         */
        internal fun syncMutation(kaKtFile: KtFile, doc: Document, mutate: () -> Unit) {
            val textBefore = kaKtFile.node.text
            mutate()
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
        internal fun textWithSeparatedTokens(file: KtFile): String {
            val sb = StringBuilder()
            var prevLast: Char? = null
            com.intellij.psi.util.PsiTreeUtil.processElements(file) { el ->
                if (el.firstChild == null) {
                    val txt = el.text
                    if (txt.isNotEmpty()) {
                        val first = txt[0]
                        when {
                            prevLast == null -> { /* first leaf, no separator needed */ }
                            el !is com.intellij.psi.PsiWhiteSpace
                                    && startsNewBlockStatement(el) && !sbEndsWithNewline(sb) ->
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
            var node: com.intellij.psi.PsiElement = leaf
            var parent = node.parent
            while (parent != null && parent !is org.jetbrains.kotlin.psi.KtBlockExpression) {
                node = parent
                parent = node.parent
            }
            if (parent !is org.jetbrains.kotlin.psi.KtBlockExpression) return false
            var first: com.intellij.psi.PsiElement = node
            while (first.firstChild != null) first = first.firstChild
            if (first !== leaf) return false
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
}
