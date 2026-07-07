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
package org.jetbrains.kotlin.formatting;

import com.intellij.formatting.FormatterImpl;
import com.intellij.formatting.Indent;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import org.jetbrains.kotlin.idea.formatter.KotlinSpacingRulesKt;
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils;
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage;
import io.github.nbplugins.kotlin.nbm.indentation.PasteAdjustmentSuppressor;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.netbeans.api.project.Project;
import org.netbeans.modules.editor.NbEditorUtilities;
import org.netbeans.modules.editor.indent.spi.Context;
import org.openide.filesystems.FileObject;
import org.openide.text.Line;

/**
 *
 * @author Alexander.Baratynski
 */
public class KotlinIndentStrategy {

    private static final char OPENING_BRACE_CHAR = '{';
    private static final char CLOSING_BRACE_CHAR = '}';
    private static final String CLOSING_BRACE_STRING = Character.toString(CLOSING_BRACE_CHAR);
    private static final String OPENING_BRACE_STRING = Character.toString(OPENING_BRACE_CHAR);

    private final StyledDocument doc;
    private final FileObject file;
    private int caretOffset;
    private int offset;
    
    public KotlinIndentStrategy(StyledDocument doc, int offset) {
        new FormatterImpl();
        this.doc = doc;
        this.file = ProjectUtils.getFileObjectForDocument(doc);
        this.offset = offset;
        caretOffset = offset;
    }

    public KotlinIndentStrategy(Context context) {
        this((StyledDocument) context.document(), context.caretOffset());
    }

    /**
     * Returns the indentation string the formatter would assign to the first line of a
     * paste at {@link #offset}, without modifying the document. Used by the paste-indent
     * filter to compute the target indent at the insertion point.
     *
     * <p>A paste at the start of a line follows the preceding line's newline, so the
     * situation is identical to pressing Enter on that (non-empty) preceding line — the
     * case {@link #autoEdit} already handles correctly. This method reproduces
     * {@code autoEdit}'s formatter input exactly: a single dummy character is placed at
     * {@code offset} (a digit after {@code =}/<code>{</code>, otherwise a space) while the
     * rest of the document — including the current line's tail, e.g. a following
     * <code>}</code> — is preserved so the formatter sees the real block structure.
     */
    public String computeIndentForPaste() throws BadLocationException {
        if (offset == 0) {
            return "";
        }
        String text = doc.getText(0, doc.getLength());
        StringBuilder textToFormat = new StringBuilder();
        textToFormat.append(text, 0, offset);
        char charToInsert = isAfterEqualityOrOpenBrace(textToFormat.toString(), textToFormat.length()) ? '1' : ' ';
        textToFormat.append(charToInsert);
        textToFormat.append(text, offset, text.length());
        return getIndent(textToFormat.toString(), offset);
    }

    public int addIndent() throws BadLocationException {
        if (offset == 1) {
            return offset;
        }
        if (offset == doc.getLength()) {
            offset--;
        }
        String text = doc.getText(0, doc.getLength());
        String commandText = String.valueOf((text).charAt(offset));

        if (isBeforeCloseBrace(text, offset, text.length()) && isAfterOpenBrace(text, offset, 0)) {
            return autoEditAfterOpenBraceAndBeforeCloseBrace(text);
        } else if(CLOSING_BRACE_STRING.equals(commandText)) {
            return autoEditBeforeCloseBrace(text);
        } else {
            return autoEdit(text);
        }
    }

    private int autoEdit(String text) throws BadLocationException {
        StringBuilder textToFormat = new StringBuilder();
        textToFormat.append(text.substring(0, caretOffset));

        char charToInsert = ' ';
        if (isAfterEqualityOrOpenBrace(textToFormat.toString(), textToFormat.length())) {
            charToInsert = '1';
        }
        textToFormat.append(charToInsert).
                append(text.substring(caretOffset));

        String indent = getIndent(textToFormat.toString(), caretOffset);
        PasteAdjustmentSuppressor.begin();
        try {
            doc.insertString(caretOffset, indent, null);
        } finally {
            PasteAdjustmentSuppressor.end();
        }

        return caretOffset + indent.length();
    }

    private int autoEditAfterOpenBraceAndBeforeCloseBrace(String text) throws BadLocationException {
        int diff = findEndOfWhiteSpaceAfter(text, offset, text.length()) - offset;
        // Resolved before getIndent(): getIndent() pushes/pops KotlinFormatterUtils'
        // thread-local settings override around its own formatter call, and that pop
        // clears the slot outright (it's not a real stack) — reading it afterwards would
        // silently fall back to the global default settings instead of any override
        // (e.g. a per-project indent size) that was active when this method was entered.
        String indentUnit = getIndentUnit();
        String indent = getIndent(text, caretOffset);
        StringBuilder builder = new StringBuilder();
        builder.append(indent).append(indentUnit).append('\n').append(indent);
        PasteAdjustmentSuppressor.begin();
        try {
            doc.remove(caretOffset, diff);
            doc.insertString(caretOffset, builder.toString(), null);
        } finally {
            PasteAdjustmentSuppressor.end();
        }
        setDocumentOffset(indent.length() + indentUnit.length());

        return caretOffset + indent.length() + indentUnit.length();
    }

    /**
     * Returns the single-level indent step (spaces or a tab, per the project's configured
     * code style) used to indent the content line of a newly-split {@code {}} block.
     * Falls back to four spaces if no project can be resolved for {@link #file}.
     */
    private String getIndentUnit() {
        Project project = ProjectUtils.getKotlinProjectForFileObject(file);
        if (project == null) {
            return "    ";
        }
        CommonCodeStyleSettings.IndentOptions indentOptions =
                ProjectCodeStyleStorage.INSTANCE.getSettings(project).getIndentOptions();
        if (indentOptions == null) {
            return "    ";
        }
        if (indentOptions.USE_TAB_CHARACTER) {
            return "\t";
        }
        int size = Math.max(1, indentOptions.INDENT_SIZE);
        StringBuilder unit = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            unit.append(' ');
        }
        return unit.toString();
    }

    private int autoEditBeforeCloseBrace(String text) throws BadLocationException {
        if (isNewLineBefore(text, caretOffset)) {
            StringBuilder oldText = new StringBuilder();

            oldText.append(text.substring(0, caretOffset - 1));
            oldText.append(CLOSING_BRACE_STRING).append(" ");
            oldText.append(text.substring(caretOffset + 1));

            if (oldText.charAt(caretOffset - 2) == '\n') {
                return caretOffset;
            }

            String indent = getIndent(oldText.toString(), caretOffset);
            PasteAdjustmentSuppressor.begin();
            try {
                doc.insertString(caretOffset, indent, null);
            } finally {
                PasteAdjustmentSuppressor.end();
            }
            return caretOffset + indent.length();
        }

        return caretOffset;
    }

    private String getIndent(String text, int offset) throws BadLocationException {
        Project project = ProjectUtils.getKotlinProjectForFileObject(file);
        if (project == null) {
            return "";
        }
        KtPsiFactory psiFactory = KotlinFormatterUtils.createPsiFactory(project);
        KtFile ktFile = KotlinFormatterUtils.createKtFile(text, psiFactory, file.getName());

        // Push per-project (or global) settings for this indent call so that getSettings()
        // returns the right CodeStyleSettings without per-keystroke I/O.
        KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.INSTANCE.getSettings(project));
        String newText;
        try {
            CodeStyleSettings settings = KotlinFormatterUtils.getSettings();
            KotlinBlock rootBlock = new KotlinBlock(ktFile.getNode(),
                    NodeAlignmentStrategy.getNullStrategy(),
                    Indent.getNoneIndent(),
                    null,
                    settings,
                    KotlinSpacingRulesKt.createSpacingBuilder(
                            settings, KotlinFormatter.KotlinSpacingBuilderUtilImpl.INSTANCE));
            newText = KotlinFormatterUtils.adjustIndent(ktFile, rootBlock, settings, offset, text);
        } finally {
            KotlinFormatterUtils.popSettings();
        }
        if (newText == null) {
            return "";
        }

        if (offset >= newText.length()) {
            return "";
        }
        String afterOffset = newText.substring(offset);

        int endOfWhiteSpace = findEndOfWhiteSpaceAfter(afterOffset, 0, afterOffset.length());
        String toReturn = afterOffset.substring(0, endOfWhiteSpace);

        return toReturn;
    }

    private static int findEndOfWhiteSpaceAfter(String document, int offset, int end) throws BadLocationException {
        while (offset < end) {
            if (!IndenterUtil.isWhiteSpaceChar(document.charAt(offset))) {
                return offset;
            }

            offset++;
        }

        return end;
    }

    private static int findEndOfWhiteSpaceBefore(String document, int offset, int start) throws BadLocationException {
        while (offset >= start) {
            if (!IndenterUtil.isWhiteSpaceChar(document.charAt(offset))) {
                return offset;
            }

            offset--;
        }

        return start;
    }

    private boolean isAfterEqualityOrOpenBrace(String text, int offset) {
        int curOffset = offset - 2;
        while (curOffset > 0) {
            char charAtCurrentOffset = text.charAt(curOffset);
            if (charAtCurrentOffset == '=' || charAtCurrentOffset == '{') {
                return true;
            } else if (charAtCurrentOffset != ' ') {
                return false;
            }
            curOffset--;
        }
        
        return false;
    }
    
    private static int findEndOfWhiteSpaceBefore(String text, int offset) {
        int curOffset = offset - 2;
        while (curOffset >= 0) {
            if (!IndenterUtil.isWhiteSpaceChar(text.charAt(curOffset))) return curOffset;
            
            curOffset--;
        }
        
        return offset;
    }
    
    private static boolean isAfterOpenBrace(String document, int offset, int startLineOffset) throws BadLocationException {
        int nonEmptyOffset = findEndOfWhiteSpaceBefore(document, offset);
        
        return document.charAt(nonEmptyOffset) == OPENING_BRACE_CHAR;
    }

    private static boolean isBeforeCloseBrace(String document, int offset, int endLineOffset) throws BadLocationException {
        int nonEmptyOffset = findEndOfWhiteSpaceAfter(document, offset, endLineOffset);
        if (nonEmptyOffset == document.length()) {
            nonEmptyOffset--;
        }
        
        return document.charAt(nonEmptyOffset) == CLOSING_BRACE_CHAR;
    }

    private static boolean isNewLineBefore(String document, int offset) {
        offset--;
        char prev = IndenterUtil.SPACE_CHAR;
        StringBuilder bufBefore = new StringBuilder(prev);
        while (IndenterUtil.isWhiteSpaceChar(prev) && offset > 0) {
            prev = document.charAt(offset--);
            bufBefore.append(prev);
        }

        return containsNewLine(bufBefore.toString());
    }

    private static boolean startsWithNewLine(String text) {
        return text.startsWith("\n");
    }

    private static boolean containsNewLine(String text) {
        return text.contains("\n");
    }

    private static int findEndOfWhiteSpace(String text, int offset) {
        while (offset > 0) {
            char c = text.charAt(offset);
            if (!IndenterUtil.isWhiteSpaceChar(c)) {
                return offset;
            }

            offset--;
        }

        return offset;
    }

    private static boolean isNewLine(String text) {
        return "\n".equals(text);
    }
    
    private void setDocumentOffset(int column) {
        Line line = NbEditorUtilities.getLine(doc, offset, false);
        line.show(Line.ShowOpenType.NONE,Line.ShowVisibilityType.NONE, column);
    }
}
