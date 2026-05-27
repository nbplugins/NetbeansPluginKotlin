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
package io.github.nbplugins.kotlin.nbm.language

import org.netbeans.spi.lexer.LanguageHierarchy
import org.netbeans.spi.lexer.LexerRestartInfo
import io.github.nbplugins.kotlin.nbm.highlighter.TokenType
import org.jetbrains.kotlin.highlighter.netbeans.KotlinLexerProxy
import org.jetbrains.kotlin.highlighter.netbeans.KotlinTokenId

class KotlinLanguageHierarchy : LanguageHierarchy<KotlinTokenId>() {

    companion object {
        private val tokens = listOf(
                KotlinTokenId(TokenType.KEYWORD.name, TokenType.KEYWORD.name, 0),
                KotlinTokenId(TokenType.IDENTIFIER.name, TokenType.IDENTIFIER.name, 1),
                KotlinTokenId(TokenType.STRING.name,TokenType.STRING.name,2),
                KotlinTokenId(TokenType.SINGLE_LINE_COMMENT.name,TokenType.SINGLE_LINE_COMMENT.name,3),
                KotlinTokenId(TokenType.MULTI_LINE_COMMENT.name,TokenType.MULTI_LINE_COMMENT.name,4),
                KotlinTokenId(TokenType.KDOC_TAG_NAME.name,TokenType.KDOC_TAG_NAME.name,5),
                KotlinTokenId(TokenType.WHITESPACE.name,TokenType.WHITESPACE.name,6),
                KotlinTokenId(TokenType.UNDEFINED.name,TokenType.UNDEFINED.name,7),
                KotlinTokenId(TokenType.ANNOTATION.name,TokenType.ANNOTATION.name,8),
                KotlinTokenId(TokenType.KDOC_LINK.name,TokenType.KDOC_LINK.name,9),
                // The category (and name) must match the `<fontcolor name>` entry in
                // FontAndColors.xml so the CSL lexer coloring resolves the correct color.
                // The IDEA-parity KOTLIN_* names are used (the older tokens above keep the
                // NetBeans-generic names KEYWORD/STRING/... which already have matching entries).
                KotlinTokenId("KOTLIN_NUMBER","KOTLIN_NUMBER",10),
                KotlinTokenId("KOTLIN_OPERATION_SIGN","KOTLIN_OPERATION_SIGN",11),
                KotlinTokenId("KOTLIN_PARENTHESIS","KOTLIN_PARENTHESIS",12),
                KotlinTokenId("KOTLIN_BRACES","KOTLIN_BRACES",13),
                KotlinTokenId("KOTLIN_BRACKETS","KOTLIN_BRACKETS",14),
                KotlinTokenId("KOTLIN_COMMA","KOTLIN_COMMA",15),
                KotlinTokenId("KOTLIN_SEMICOLON","KOTLIN_SEMICOLON",16),
                KotlinTokenId("KOTLIN_DOT","KOTLIN_DOT",17),
                KotlinTokenId("KOTLIN_ARROW","KOTLIN_ARROW",18),
                KotlinTokenId("KOTLIN_STRING_ESCAPE","KOTLIN_STRING_ESCAPE",19)
        )
        
        fun getToken(id: Int) = tokens.first { it.ordinal() == id }
    }
    
    override fun createTokenIds() = tokens
    override fun createLexer(info: LexerRestartInfo<KotlinTokenId>) = KotlinLexerProxy(info)
    override fun mimeType() = "text/x-kotlin"
}