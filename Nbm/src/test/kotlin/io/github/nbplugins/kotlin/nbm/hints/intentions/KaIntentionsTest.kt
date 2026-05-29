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

    // ── Group C — Lambda / function reference ─────────────────────────────────

    /** K2 path for "Convert lambda to reference". */
    fun testKaConvertLambdaToReference() = doTest("kaConvertLambdaToReference") { doc, kaKtFile, psi ->
        KaConvertLambdaToReferenceIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert reference to lambda". */
    fun testKaConvertReferenceToLambda() = doTest("kaConvertReferenceToLambda") { doc, kaKtFile, psi ->
        KaConvertReferenceToLambdaIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Convert to anonymous function". */
    fun testKaLambdaToAnonymousFunction() = doTest("kaLambdaToAnonymousFunction") { doc, kaKtFile, psi ->
        KaLambdaToAnonymousFunctionIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Put lambda body on multiple lines". */
    fun testKaConvertLambdaToMultiLine() = doTest("kaConvertLambdaToMultiLine") { doc, kaKtFile, psi ->
        KaConvertLambdaToMultiLineIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Put lambda body on one line". */
    fun testKaConvertLambdaToSingleLine() = doTest("kaConvertLambdaToSingleLine") { doc, kaKtFile, psi ->
        KaConvertLambdaToSingleLineIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Replace 'forEach' with a 'for' loop". */
    fun testKaConvertForEachToForLoop() = doTest("kaConvertForEachToForLoop") { doc, kaKtFile, psi ->
        KaConvertForEachToForLoopIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Replace 'for' loop with 'forEach' call". */
    fun testKaConvertToForEachFunctionCall() = doTest("kaConvertToForEachFunctionCall") { doc, kaKtFile, psi ->
        KaConvertToForEachFunctionCallIntention(doc, kaKtFile, psi)
    }

    // ── Group D — Named call arguments ────────────────────────────────────────

    /** K2 path for "Add names to call arguments". */
    fun testKaAddNamesToCallArguments() = doTest("kaAddNamesToCallArguments") { doc, kaKtFile, psi ->
        KaAddNamesToCallArgumentsIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Add names to this argument and following arguments". */
    fun testKaAddNamesToFollowingArguments() = doTest("kaAddNamesToFollowingArguments") { doc, kaKtFile, psi ->
        KaAddNamesToFollowingArgumentsIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Add name to argument". */
    fun testKaAddNameToArgument() = doTest("kaAddNameToArgument") { doc, kaKtFile, psi ->
        KaAddNameToArgumentIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Remove all argument names". */
    fun testKaRemoveAllArgumentNames() = doTest("kaRemoveAllArgumentNames") { doc, kaKtFile, psi ->
        KaRemoveAllArgumentNamesIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Remove argument name". */
    fun testKaRemoveSingleArgumentName() = doTest("kaRemoveSingleArgumentName") { doc, kaKtFile, psi ->
        KaRemoveSingleArgumentNameIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Put calls on separate lines". */
    fun testKaPutCallsOnSeparateLines() = doTest("kaPutCallsOnSeparateLines") { doc, kaKtFile, psi ->
        KaPutCallsOnSeparateLinesIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Put arguments on separate lines". */
    fun testKaChopArgumentList() = doTest("kaChopArgumentList") { doc, kaKtFile, psi ->
        KaChopArgumentListIntention(doc, kaKtFile, psi)
    }

    /** K2 path for "Put arguments on one line". */
    fun testKaJoinArgumentList() = doTest("kaJoinArgumentList") { doc, kaKtFile, psi ->
        KaJoinArgumentListIntention(doc, kaKtFile, psi)
    }
}
