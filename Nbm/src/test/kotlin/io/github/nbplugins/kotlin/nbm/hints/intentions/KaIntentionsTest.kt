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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import io.github.nbplugins.kotlin.nbm.hints.KaIntentionTestBase

/**
 * Integration tests for K2 [io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention] subclasses.
 *
 * Each test reuses the same fixture files from `projForTest/src/intentions/` that the K1
 * [intentions.IntentionsTest] uses, verifying that the K2 port produces the same result.
 *
 * Tests skip gracefully when the K2 session has no binary dependencies (no stdlib on classpath).
 */
class KaIntentionsTest : KaIntentionTestBase("KaIntentions test", "intentions") {

    /** K2 path for "Specify type explicitly". */
    fun testKaSpecifyType() = doTest("specifyType") { doc, kaKtFile, psi ->
        KaSpecifyTypeIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Remove explicit type specification". */
    fun testKaRemoveExplicitType() = doTest("removeExplicitType") { doc, kaKtFile, psi ->
        KaRemoveExplicitTypeIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert to block body". */
    fun testKaConvertToBlockBody() = doTest("convertToBlockBody") { doc, kaKtFile, psi ->
        KaConvertToBlockBodyIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert to expression body". */
    fun testKaConvertToExpressionBody() = doTest("convertToExpressionBody") { doc, kaKtFile, psi ->
        KaConvertToExpressionBodyIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Change function return type". */
    fun testKaChangeReturnType() = doTest("changeReturnType") { doc, kaKtFile, psi ->
        KaChangeReturnTypeIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Replace size check with isNotEmpty". */
    fun testKaReplaceSizeCheckWithIsNotEmpty() = doTest("replaceSizeCheckWithIsNotEmpty") { doc, kaKtFile, psi ->
        KaReplaceSizeCheckWithIsNotEmptyIntention(doc, kaKtFile, psi)
    }
}
