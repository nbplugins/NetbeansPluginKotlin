/*******************************************************************************
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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.highlighter

import org.jetbrains.kotlin.lexer.KtTokens
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens

class KotlinTokensFactory {

    fun getToken(leafElement: PsiElement): TokenType {
        if (leafElement !is LeafPsiElement) return TokenType.UNDEFINED

        val elementType = leafElement.elementType
        return when (elementType) {
            in KtTokens.KEYWORDS, in KtTokens.SOFT_KEYWORDS, in KtTokens.MODIFIER_KEYWORDS -> TokenType.KEYWORD
            in KtTokens.STRINGS, KtTokens.OPEN_QUOTE, KtTokens.CLOSING_QUOTE -> TokenType.STRING
            KtTokens.ESCAPE_SEQUENCE -> TokenType.STRING_ESCAPE
            KtTokens.IDENTIFIER -> TokenType.IDENTIFIER
            in KtTokens.WHITESPACES -> TokenType.WHITESPACE
            KtTokens.EOL_COMMENT -> TokenType.SINGLE_LINE_COMMENT
            in KtTokens.COMMENTS, in KDocTokens.KDOC_HIGHLIGHT_TOKENS -> TokenType.MULTI_LINE_COMMENT
            KDocTokens.TAG_NAME -> TokenType.KDOC_LINK
            KtTokens.INTEGER_LITERAL, KtTokens.FLOAT_LITERAL, KtTokens.CHARACTER_LITERAL -> TokenType.NUMBER
            KtTokens.LPAR, KtTokens.RPAR -> TokenType.PARENTHESIS
            KtTokens.LBRACE, KtTokens.RBRACE -> TokenType.BRACES
            KtTokens.LBRACKET, KtTokens.RBRACKET -> TokenType.BRACKETS
            KtTokens.COMMA -> TokenType.COMMA
            KtTokens.SEMICOLON -> TokenType.SEMICOLON
            KtTokens.DOT, KtTokens.SAFE_ACCESS -> TokenType.DOT
            KtTokens.ARROW -> TokenType.ARROW
            KtTokens.PLUSPLUS, KtTokens.MINUSMINUS, KtTokens.MUL, KtTokens.PLUS, KtTokens.MINUS,
            KtTokens.EXCL, KtTokens.DIV, KtTokens.PERC, KtTokens.LT, KtTokens.GT,
            KtTokens.LTEQ, KtTokens.GTEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ,
            KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.ANDAND, KtTokens.OROR,
            KtTokens.ELVIS, KtTokens.EQ, KtTokens.MULTEQ, KtTokens.DIVEQ,
            KtTokens.PERCEQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.RANGE,
            KtTokens.RANGE_UNTIL, KtTokens.COLON, KtTokens.COLONCOLON,
            KtTokens.QUEST, KtTokens.EXCLEXCL -> TokenType.OPERATOR
            else -> TokenType.UNDEFINED
        }
    }

}