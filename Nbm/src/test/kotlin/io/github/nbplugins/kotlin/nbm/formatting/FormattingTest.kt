/**
 * *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 ******************************************************************************
 */


package io.github.nbplugins.kotlin.nbm.formatting

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import javaproject.JavaProject
import javax.swing.text.Document
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.refactoring.formatExtractSuperText
import io.github.nbplugins.kotlin.nbm.reformatting.FormatCaretPolicy
import io.github.nbplugins.kotlin.nbm.reformatting.applyFormatCaretPolicy
import io.github.nbplugins.kotlin.nbm.reformatting.applyFormattedText
import org.jetbrains.kotlin.formatting.NetBeansDocumentFormattingModel
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject
import utils.*

/**
 *
 * @author Alexander.Baratynski
 */
class FormattingTest : KotlinTestCase("Formatting test", "formatting") {
    
    private fun doTest(fileName: String) {
        val doc = getDocumentForFileObject(dir, fileName)
        val file = ProjectUtils.getFileObjectForDocument(doc)
        val parsedFile = ProjectUtils.getKtFile(doc.getText(0, doc.length), file)
        val code = parsedFile.text
            
        val formattedCode = KotlinFormatterUtils.formatCode(code, parsedFile.name, project, "\n")
        val doc2 = getDocumentForFileObject(dir, fileName.replace(".kt", ".after"))
        val after = doc2.getText(0, doc2.length)
        assertEquals(after, formattedCode)
    }

    fun testBlockCommentBeforeDeclaration() = doTest("blockCommentBeforeDeclaration.kt")

    fun testClassesAndPropertiesFormatTest() = doTest("classesAndPropertiesFormatTest.kt")

    fun testCommentOnTheLastLineOfLambda() = doTest("commentOnTheLastLineOfLambda.kt")

    fun testIndentInDoWhile() = doTest("indentInDoWhile.kt")

    fun testIndentInIfExpressionBlock() = doTest("indentInIfExpressionBlock.kt")

    fun testIndentInPropertyAccessor() = doTest("indentInPropertyAccessor.kt")

    fun testIndentInWhenEntry() = doTest("indentInWhenEntry.kt")

    fun testInitIndent() = doTest("initIndent.kt")

    fun testLambdaInBlock() = doTest("lambdaInBlock.kt")

    fun testNewLineAfterImportsAndPackage() = doTest("newLineAfterImportsAndPackage.kt")

    fun testObjectsAndLocalFunctionsFormat() = doTest("objectsAndLocalFunctionsFormatTest.kt")

    fun testPackageFunctions() = doTest("packageFunctionsFormatTest.kt")

    fun testClassInBlockComment() = doTest("withBlockComments.kt")

    fun testJavaDoc() = doTest("withJavaDoc.kt")

    fun testLineComments() = doTest("withLineComments.kt")

    fun testMutableVariable() = doTest("withMutableVariable.kt")

    fun testWhitespaceBeforeBrace() = doTest("withWhitespaceBeforeBrace.kt")

    fun testWhithoutComments() = doTest("withoutComments.kt")

    /**
     * Verifies that the NetBeans formatter normalizes the raw PSI shape emitted by IDEA K2 Extract
     * Super before the refactoring adapter persists source and target files. The copied engine uses
     * IntelliJ's {@code CodeStyleManager}, which is intentionally a no-op in standalone mode.
     */
    fun testFormatExtractSuperOutput_normalizesGeneratedInterfaceAndInheritance() {
        val rawSource = """
            package extractsuper.same

            class Greeter :extractsuper.same.IGreeter{
                fun greet(): String = \"hello\"
            }
        """.trimIndent()
        val rawTarget = """
            package extractsuper.same

            interface IGreeter{
            fun greet(): String
            }
        """.trimIndent()

        val formattedSource = formatExtractSuperText(rawSource, "Greeter.kt", project)
        val formattedTarget = formatExtractSuperText(rawTarget, "IGreeter.kt", project)

        assertTrue("Expected formatted inheritance clause, got:\n$formattedSource", formattedSource.contains("class Greeter : extractsuper.same.IGreeter {"))
        assertTrue("Expected formatted interface body, got:\n$formattedTarget", formattedTarget.contains("interface IGreeter {"))
        assertTrue("Expected interface member to remain formatted, got:\n$formattedTarget", formattedTarget.contains("fun greet(): String"))
    }

    /** Verifies default interactive formatting explicitly moves the editor caret. */
    fun testFormatCaretPolicy_movesToOffsetByDefault() {
        val doc = javax.swing.text.PlainDocument()
        var movedTo: Int? = null

        applyFormatCaretPolicy(doc, 23, FormatCaretPolicy.MOVE_TO_OFFSET) { offset -> movedTo = offset }

        assertEquals(23, movedTo)
    }

    /** Verifies background hunk formatting never requests editor navigation. */
    fun testFormatCaretPolicy_preservesCaretForBackgroundFormatting() {
        val doc = javax.swing.text.PlainDocument()
        var moved = false

        applyFormatCaretPolicy(doc, 7, FormatCaretPolicy.PRESERVE) { moved = true }

        assertFalse("preserve policy must not navigate the editor", moved)
    }

    /** Verifies formatter output applies only the local changed hunk, not a document reset. */
    fun testApplyFormattedText_replacesOnlyChangedHunk() {
        val document = RecordingDocument()
        val original = "fun main(){\n    println(1)\n}\n"
        val formatted = "fun main() {\n    println(1)\n}\n"
        document.insertString(0, original, null)
        document.events.clear()

        applyFormattedText(document, original, formatted)

        assertEquals(formatted, document.getText(0, document.length))
        assertFalse("formatting must not remove the complete original document", document.events.contains("remove:0:${original.length}"))
        assertEquals("only the inserted formatter whitespace should be written", listOf("insert:10:1"), document.events)
    }

    /** Verifies identical formatter output produces no document mutations. */
    fun testApplyFormattedText_isNoOpForIdenticalText() {
        val document = RecordingDocument()
        val text = "fun main() = Unit\n"
        document.insertString(0, text, null)
        document.events.clear()

        applyFormattedText(document, text, text)

        assertEquals(text, document.getText(0, document.length))
        assertTrue("identical formatter output must make no edits", document.events.isEmpty())
    }

    /** Verifies multiple independent formatter changes retain untouched text between their hunks. */
    fun testApplyFormattedText_appliesSeparatedHunks() {
        val document = RecordingDocument()
        val original = "fun first(){ }\nval untouched = \"keep\"\nfun second(){ }\n"
        val formatted = "fun first() { }\nval untouched = \"keep\"\nfun second() { }\n"
        document.insertString(0, original, null)
        document.events.clear()

        applyFormattedText(document, original, formatted)

        assertEquals(formatted, document.getText(0, document.length))
        assertFalse("unchanged middle content must not be removed", document.events.any { it == "remove:${original.indexOf("val untouched")}:${"val untouched = \"keep\"\n".length}" })
    }

    /**
     * Verifies a caret tracked below a formatter change keeps its logical text position instead of
     * being swept to the end of the document by a whole-document replacement.
     */
    fun testApplyFormattedText_keepsTrackedPositionBelowChange() {
        val document = javax.swing.text.PlainDocument()
        val original = "fun main(){\n    println(1)\n}\n"
        val formatted = "fun main() {\n    println(1)\n}\n"
        document.insertString(0, original, null)
        val caret = document.createPosition(original.length - 2)

        applyFormattedText(document, original, formatted)

        assertEquals("the tracked caret must follow the inserted space", formatted.length - 2, caret.offset)
    }

    /** Records only direct document mutations so the formatter applier can be tested without an editor pane. */
    private class RecordingDocument : javax.swing.text.PlainDocument() {
        val events = mutableListOf<String>()

        override fun remove(offs: Int, len: Int) {
            events += "remove:$offs:$len"
            super.remove(offs, len)
        }

        override fun insertString(offs: Int, str: String?, a: javax.swing.text.AttributeSet?) {
            events += "insert:$offs:${str?.length ?: 0}"
            super.insertString(offs, str, a)
        }
    }

    /**
     * Verifies that [KotlinFormatterUtils.formatRange] reformats only the selected
     * character range while leaving code outside the range unchanged.
     */
    fun testFormatRange() {
        // Source with two functions: fun a is well-formatted, fun b is deliberately messy.
        val source = "fun a() {\n    val x = 1\n}\n\nfun b(  ){\nval   y    =    2\n}\n"
        val rangeStart = source.indexOf("fun b")
        val psiFactory = KotlinFormatterUtils.createPsiFactory(project)
        val result = KotlinFormatterUtils.formatRange(
            source, TextRange(rangeStart, source.length), psiFactory, "test.kt"
        )
        // Code before the selection must remain byte-identical.
        assertTrue("Code before selection unchanged", result.startsWith("fun a() {\n    val x = 1\n}"))
        // fun b should have its signature normalised by the formatter.
        assertTrue("fun b signature formatted", result.contains("fun b()"))
    }

    /**
     * Verifies that when the selection end is trimmed to exclude trailing newlines,
     * the first line of the function immediately after the selection is not reformatted.
     *
     * This guards against the IntelliJ formatter "bleeding" into the next sibling when
     * `endOffset` points past the closing brace into the inter-function whitespace.
     */
    fun testFormatRangeDoesNotTouchNextFunction() {
        // farewell has messy formatting; test has deliberately messy signature.
        val source = "fun farewell(){\nreturn \"bye\"\n}\n\nfun test(  ){\n}\n"
        // Trim the end to stop before the blank lines that separate the two functions,
        // mirroring what format() in formatUtils.kt does before calling formatRange().
        val trimmedEnd = source.indexOf("\n\nfun test")
        val psiFactory = KotlinFormatterUtils.createPsiFactory(project)
        val result = KotlinFormatterUtils.formatRange(
            source, TextRange(0, trimmedEnd), psiFactory, "test.kt"
        )
        // farewell should be formatted.
        assertTrue("farewell formatted", result.contains("fun farewell() {"))
        // fun test's messy signature must not have been touched by the formatter.
        assertTrue("fun test not reformatted", result.contains("fun test(  )"))
    }

}
