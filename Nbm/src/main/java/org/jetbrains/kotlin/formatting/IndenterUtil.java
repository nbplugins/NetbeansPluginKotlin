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

/**
 * Whitespace predicates and constants used by the Kotlin formatter pipeline.
 */
public class IndenterUtil {
    public static final char SPACE_CHAR = ' ';
    public static final char TAB_CHAR = '\t';
    public static final String TAB_STRING = Character.toString(TAB_CHAR);

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
