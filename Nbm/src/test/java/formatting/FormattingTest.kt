/**
 * *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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


package formatting

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import javaproject.JavaProject
import javax.swing.text.Document
import org.jetbrains.kotlin.formatting.KotlinFormatterUtils
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
