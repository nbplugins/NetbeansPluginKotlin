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
package org.jetbrains.kotlin.formatting;

import com.intellij.psi.impl.source.tree.LeafPsiElement;
import org.jetbrains.kotlin.utils.LineEndUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.netbeans.api.editor.mimelookup.MimeLookup;
import org.netbeans.api.editor.mimelookup.MimePath;
import org.netbeans.api.editor.settings.SimpleValueNames;
import java.util.prefs.Preferences;

/**
 * Utility class for indentation operations. All indent parameters are read from
 * NetBeans editor preferences for the {@code text/x-kotlin} MIME type so that
 * user settings in Tools → Options → Editor are respected.
 */
public class IndenterUtil {
    public static final char SPACE_CHAR = ' ';
    public static final char TAB_CHAR = '\t';
    public static final String TAB_STRING = Character.toString(TAB_CHAR);

    /** Fallback indent size when NetBeans preferences are unavailable. */
    private static final int FALLBACK_INDENT = 4;

    /** Returns the NetBeans editor preferences node for {@code text/x-kotlin}. */
    private static Preferences kotlinPrefs() {
        return MimeLookup.getLookup(MimePath.parse("text/x-kotlin"))
                         .lookup(Preferences.class);
    }

    /**
     * Returns the indent size configured in Tools → Options → Editor → Formatting
     * for Kotlin files. Falls back to {@value #FALLBACK_INDENT} if the preference
     * node is not available.
     *
     * @return number of spaces (or tab-equivalent columns) per indent level
     */
    public static int getDefaultIndent() {
        Preferences prefs = kotlinPrefs();
        if (prefs == null) {
            return FALLBACK_INDENT;
        }
        return prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, FALLBACK_INDENT);
    }

    /**
     * Returns {@code true} when the Kotlin editor is configured to use spaces
     * for indentation, {@code false} when tabs are used. Reads from NetBeans
     * editor preferences; defaults to {@code true} (spaces) if unavailable.
     *
     * @return {@code true} if spaces should be used for indentation
     */
    public static boolean isSpacesForTabs() {
        Preferences prefs = kotlinPrefs();
        if (prefs == null) {
            return true;
        }
        return prefs.getBoolean(SimpleValueNames.EXPAND_TABS, true);
    }

    /**
     * Returns the tab size configured for the Kotlin editor. Falls back to
     * {@value #FALLBACK_INDENT} if preferences are unavailable.
     *
     * @return number of columns per tab character
     */
    public static int getTabSize() {
        Preferences prefs = kotlinPrefs();
        if (prefs == null) {
            return FALLBACK_INDENT;
        }
        return prefs.getInt(SimpleValueNames.TAB_SIZE, FALLBACK_INDENT);
    }

    /**
     * Creates a whitespace string for the given indentation level and line-break count.
     *
     * @param curIndent      indentation level (number of indent steps)
     * @param countBreakLines number of leading newlines to insert
     * @param lineSeparator  line separator string
     * @return whitespace string combining newlines and indent
     */
    public static String createWhiteSpace(int curIndent, int countBreakLines, String lineSeparator) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < countBreakLines; i++) {
            stringBuilder.append(lineSeparator);
        }

        String whiteSpace = getIndentString();
        for (int i = 0; i < curIndent; i++) {
            stringBuilder.append(whiteSpace);
        }

        return stringBuilder.toString();
    }

    /**
     * Returns the indent string for one indent level — either a sequence of spaces
     * or a single tab character, according to the current editor preferences.
     *
     * @return indent string for one indentation level
     */
    public static String getIndentString() {
        if (isSpacesForTabs()) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < getDefaultIndent(); i++) {
               result.append(SPACE_CHAR);
            }
            return result.toString();
        } else {
            return TAB_STRING;
        }
    }

    /**
     * Counts the number of newline characters in the given text.
     *
     * @param text text to scan
     * @return count of {@code \n} characters
     */
    public static int getLineSeparatorsOccurences(String text) {
        int result = 0;

        for (char c : text.toCharArray()) {
            if (c == LineEndUtil.NEW_LINE_CHAR) {
                result++;
            }
        }

        return result;
    }

    /**
     * Returns {@code true} if the given PSI element is a whitespace node that
     * contains a newline character.
     *
     * @param psiElement leaf PSI element to inspect
     * @return {@code true} if the element represents a newline
     */
    public static boolean isNewLine(LeafPsiElement psiElement) {
        return psiElement.getElementType() == KtTokens.WHITE_SPACE && psiElement.getText().contains(LineEndUtil.NEW_LINE_STRING);
    }

    /**
     * Returns {@code true} if the character is a space or tab.
     *
     * @param c character to test
     * @return {@code true} if {@code c} is a whitespace character
     */
    public static boolean isWhiteSpaceChar(char c) {
        return c == SPACE_CHAR || c == TAB_CHAR;
    }

    /**
     * Returns {@code true} if the character is a space, tab, or newline.
     *
     * @param c character to test
     * @return {@code true} if {@code c} is whitespace or a newline
     */
    public static boolean isWhiteSpaceOrNewLine(char c) {
        return c == SPACE_CHAR || c == TAB_CHAR || c == LineEndUtil.NEW_LINE_CHAR;
    }
}
