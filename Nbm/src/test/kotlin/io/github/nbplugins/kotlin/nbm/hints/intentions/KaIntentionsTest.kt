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

    /** K2 path for "Add remaining when branches". */
    fun testKaAddWhenRemainingBranches() = doTest("addWhenRemainingBranches") { doc, kaKtFile, psi ->
        KaAddWhenRemainingBranchesIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Merge ifs". */
    fun testKaMergeIfs() = doTest("mergeIfs") { doc, kaKtFile, psi ->
        KaMergeIfsIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Split if into two". */
    fun testKaSplitIf() = doTest("splitIf") { doc, kaKtFile, psi ->
        KaSplitIfIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Invert 'if' condition". */
    fun testKaInvertIfCondition() = doTest("invertIfCondition") { doc, kaKtFile, psi ->
        KaInvertIfConditionIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Replace 'if' with 'when'". */
    fun testKaIfToWhen() = doTest("ifToWhen") { doc, kaKtFile, psi ->
        KaIfToWhenIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Replace 'when' with 'if'". */
    fun testKaWhenToIf() = doTest("whenToIf") { doc, kaKtFile, psi ->
        KaWhenToIfIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Flatten 'when' expression". */
    fun testKaFlattenWhen() = doTest("flattenWhen") { doc, kaKtFile, psi ->
        KaFlattenWhenIntention(doc, kaKtFile, psi)
    }

    // ── Group B — String transformations ─────────────────────────────────────

    /** K2 path for "Convert concatenation to template". */
    fun testKaConvertToStringTemplate() = doTest("convertToStringTemplate") { doc, kaKtFile, psi ->
        KaConvertToStringTemplateIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert string template to concatenated string". */
    fun testKaConvertToConcatenatedString() = doTest("convertToConcatenatedString") { doc, kaKtFile, psi ->
        KaConvertToConcatenatedStringIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert concatenation to raw string". */
    fun testKaConvertToRawStringTemplate() = doTest("kaConvertToRawStringTemplate") { doc, kaKtFile, psi ->
        KaConvertToRawStringTemplateIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert to raw string literal". */
    fun testKaToRawStringLiteral() = doTest("kaToRawStringLiteral") { doc, kaKtFile, psi ->
        KaToRawStringLiteralIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert concatenation to 'buildString'". */
    fun testKaConvertConcatenationToBuildString() = doTest("kaConvertConcatenationToBuildString") { doc, kaKtFile, psi ->
        KaConvertConcatenationToBuildStringIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert string template to 'buildString'". */
    fun testKaConvertStringTemplateToBuildString() = doTest("kaConvertStringTemplateToBuildString") { doc, kaKtFile, psi ->
        KaConvertStringTemplateToBuildStringIntention(doc, kaKtFile, psi)
    }
}
