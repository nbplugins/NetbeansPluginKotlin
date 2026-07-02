# E9 — Port IDEA refactoring engines directly (Extract Function first)

Status: **Phase 0 complete** — see findings below. Phase 1+ (generator port) still to do.

## Goal

Stop hand-imitating IDEA refactoring behaviour and drive the **actual IntelliJ IDEA K2
refactoring engine** from the submodule, starting with **Extract Function** — the one whose
imitation is fundamentally broken. Concretely, after this work Extract Function must produce, for

```kotlin
fun outer() {
    val a = 1
    val b = 2
    println(a + b)   // ← selection
}
```

the same result IDEA's "Extract function to scope" produces:

```kotlin
fun outer() {
    val a = 1
    val b = 2
    extracted(a, b)
}

private fun extracted(a: Int, b: Int) {
    println(a + b)
}
```

i.e. **captured locals become parameters**, the call site passes them as arguments, the new
function is correctly placed and formatted, and undo still restores text + caret (already handled by
`CaretRestoreOnUndo`).

## Background: what is actually imitated today

Contrary to first impressions, the **analysis** half already uses the real IDEA engine.
`KaExtractFunctionComputer` (`KotlinRefactoring/.../KaExtractFunctionComputer.kt`) calls
`ExtractionDataAnalyzer(ExtractionData(...)).performAnalysis()` and reads
`ExtractableCodeDescriptor.parameters`. The **generation** half is hand-written:
`KotlinExtractFunctionPlugin.performChange()` builds the function text with string templates
(`"fun $name($paramList)$returnClause { … }"`) and the call site by hand.

`KotlinRefactoring/pom.xml` already copies (via `maven-resources`) the *analysis* subset of the
engine, but deliberately **excludes the generation engine**. Ported today:

- common `extractionEngine`: `AbstractExtractionDataAnalyzer`, `AnalysisResult`, `ControlFlow(+Builder)`,
  `ExtractionTarget`, `extractUtil`, `ParametersInfo`, `OutputValue(+Boxer)`, `OutputDescriptor`,
  `TypeDescriptor`, `TypeParameter`, all `I*` interfaces, `ExtractionOptions`,
  `ExtractionGeneratorOptions`, `encodeDecodeUtil`, `DuplicateInfo`, `ExtractableCodeDescriptorWithConflictsResult`.
- k2 `extractFunction`: `ExtractableCodeDescriptor`, `ExtractionData`, `ExtractionGeneratorConfiguration`,
  `ExtractionResult`, `Parameter`, `parametersUtil`, `ExtractFunctionDescriptorModifier`.
- k2 `extractionEngine`: `ExtractionDataAnalyzer`, `KotlinNameSuggester`, `KotlinTypeDescriptor`.

**Not ported (the gap = generation):**
- common: `ExtractFunctionGenerator.kt` (the real generator — `generateDeclaration(config, …)` that
  creates the function PSI, rewrites the call, boxes output values), `IExtractionEngine.kt`,
  `PostInsertDeclarationCallback.kt`, `duplicateUtil.kt`.
- k2: `Generator.kt` (`object Generator : ExtractFunctionGenerator<KaType, ExtractionResult>`,
  entry `Generator.generateDeclaration(config, null)`), `extractionEngineUtil.kt`,
  `ExtractionEngineHelper.kt`.

## Root cause of the observed bug

Because generation is manual, parameters/arguments are only as good as what the plugin renders from
`descriptor.parameters`, and the observed output had **no parameters** and **wrong indentation**.
Two possibilities, to be confirmed in Phase 0:
1. `descriptor.parameters` is actually populated but the manual generator drops/ignores it, or
2. in the standalone/headless K2 session the analyzer returns an empty parameter list (some
   service the analyzer relies on is missing).

Either way the fix is the same strategically: **drive IDEA's generator** instead of emitting text
by hand.

## Key integration challenge

IDEA's `ExtractFunctionGenerator.generateDeclaration` **mutates PSI in place** (`anchor.replace(psiFactory.createExpression(callText))`, inserts the new declaration, `shortenReferences`,
reformat) inside a write action. Our refactorings instead edit the **NetBeans editor `Document`**;
the standalone-analysis `KtFile` is not the editor's document and may be read-only.

**Chosen approach — mutate an in-memory copy, then sync text:**
1. Build a **writable** `KtFile` copy of the source in the analysis project (via `KtPsiFactory` /
   a light in-memory `VirtualFile`), so the engine can mutate it freely.
2. Run analysis + `Generator.generateDeclaration(...)` on that copy inside a write action.
3. Read the resulting file **text**, compute a minimal diff against the original
   (reuse `MinimalDocumentEdits` / a longest-common-prefix-suffix diff), and apply those edits to
   the real NetBeans `Document` within the existing atomic-lock body — so `joinCaretRestoreOnUndo`
   and undo continue to work unchanged.
4. Derive the post-refactor caret (call site / new function name) from the engine result offsets.

This keeps all editor/undo integration in `Nbm` and treats the ported engine as a pure
"text in → text out" transformation.

## Phased plan

### Phase 0 — Diagnostic spike (no production changes)
- Add a temporary unit test in `KotlinRefactoring` (or `Nbm`) that runs the current analyzer on the
  `outer()` fixture and asserts whether `descriptor.parameters` contains `a`, `b`.
- Attempt to invoke `Generator.generateDeclaration` (compiled ad-hoc) on a writable copy headlessly
  and record which services/extension points are missing (`PsiDocumentManager`, `CodeStyleManager`,
  write-action/command infra, `shortenReferences`).
- **Output:** a short findings note appended here deciding (a) whether analysis is the problem and
  (b) the list of runtime stubs/service registrations generation needs. Gate the rest on this.

### Phase 1 — Port the generation engine (build only)
- Extend `KotlinRefactoring/pom.xml` `maven-resources` includes with the generation files listed
  above (common `ExtractFunctionGenerator`, `IExtractionEngine`, `PostInsertDeclarationCallback`,
  `duplicateUtil`; k2 `Generator`, `extractionEngineUtil`, `ExtractionEngineHelper`).
- Resolve transitive compile deps by adding only the minimal extra submodule files they need
  (iterate: compile → add missing source → repeat). Explicitly **exclude** UI/editor/action files
  (`KotlinFirExtractFunctionHandler` UI parts, `ui/*`, `ExtractK2Function*Action`).
- Add any required runtime service registrations to the standalone session (mirror the existing
  `KotlinShortenReferencesFacility` / name-validator registrations in `KotlinAnalysisAPISession`).
- **Done when:** `mvn install` builds `KotlinRefactoring` with the generator on the classpath.

### Phase 2 — Rewrite `KaExtractFunctionComputer` to generate via the engine
- Replace `buildResult`'s manual data extraction with: build `ExtractionGeneratorConfiguration`
  from the analysed `ExtractableCodeDescriptor` (name, target = local/member/top-level per chosen
  scope, options), run `Generator.generateDeclaration(config, null)` on the writable copy.
- New result type carries **final file text** (or precomputed minimal edits) + caret offset, instead
  of `parameters`/`returnTypeText`/`selectionText`.
- Keep `collectScopeCandidates()` (PSI-only) for the destination combo box; map the chosen scope to
  the engine's `ExtractionTarget` / target sibling.

### Phase 3 — Simplify `KotlinExtractFunctionPlugin.performChange`
- Delete the hand-rolled function/call string building.
- Apply the engine's text result to the `Document` via minimal edits inside the atomic body, keep
  `joinCaretRestoreOnUndo`, set the forward caret to the engine-reported call/name offset.
- `undoChange` snapshot fallback stays.

### Phase 4 — Tests & manual verification
- `KaExtractFunctionTest`: extend fixtures for captured-parameter cases (the `outer()` example),
  a return-value case (`val r = a + b`), multi-statement selection, and each destination
  (local / member / top-level). Assert the generated text contains `extracted(a, b)` and
  `fun extracted(a: Int, b: Int)`.
- Update `docs/e9-refactoring-undo-caret-test-plan.md` Extract Function section to expect parameters.
- Full `mvn test`; manual test in NetBeans; version bump per usual.

## After Extract Function: the other refactorings

Extract Function is the only one whose imitation is *functionally* wrong. Introduce
Variable/Constant/Property/Type Alias/Import Alias currently produce correct results (their caret and
formatting bugs are fixed). Porting them to IDEA handlers is **optional polish**, not a correctness
need. If pursued later, apply the same "mutate in-memory copy → sync text" pattern with the
respective IDEA K2 introduce handlers, one refactoring per branch/PR, each with fixtures. Decide
per-refactoring whether the port earns its risk.

## Risks & open questions
- **Headless generation** may need services not present in the standalone session (formatting,
  write actions, `PsiDocumentManager`). Phase 0 must de-risk this before committing to Phase 1–3.
- **Transitive source sprawl:** the generator may pull in more submodule files than expected; cap by
  adding sources iteratively and excluding UI.
- **Formatting**: the engine reformats via `CodeStyleManager`; confirm it uses the bundled
  `KotlinFormatter` path and not an unregistered service.
- **Writable copy fidelity:** offsets in the engine result are in the *copy*; the diff-to-Document
  step must map them correctly (prefer computing edits from full text rather than trusting offsets).

## Phase 0 findings (2026-07-02)

The diagnostic spike found the "no parameters" bug was **neither** of the two hypotheses in
"Root cause of the observed bug" above. It was two small, independent defects in our own
`KaExtractFunctionComputer.kt` — not in the ported analysis engine, and not caused by manual
generation:

1. **`findElementAtStartOffset` over-climbed into scope boundaries.** When a user-selected
   destination scope's anchor offset coincided with the start offset of its own container (e.g.
   extracting to top-level when the target function is the *first* declaration in the file — the
   `outer()` fixture exactly), the offset-climbing loop didn't stop at the container boundary and
   returned the `KtFile`/`KtBlockExpression`/`KtClassBody` itself instead of the intended child
   declaration. That invalid `targetSibling` then NPE'd inside the genuinely-ported
   `createTemporaryDeclaration` (`extractUtil.kt:52`). **Fixed**: the climb now stops before
   crossing into `KtBlockExpression`/`KtFile`/`KtClassBody`.

2. **`findElements()` decomposed a single-expression selection into non-`KtExpression` children.**
   For a selection matching a whole call expression (`println(a + b)`), `findElements()` always
   collected `commonParent.children` — for a `KtCallExpression` that's `[calleeExpression,
   valueArgumentList]`. `KtValueArgumentList` is **not** a `KtExpression`, so
   `ExtractionData.expressions = originalElements.filterIsInstance<KtExpression>()` silently
   dropped it, leaving only the bare `println` callee reference. The real (genuinely ported)
   `encodeReferences`/`inferParametersInfo` pipeline then never even visited `a`/`b` — they were
   never part of the analyzed expression set, not "filtered out" by any resolution logic. **Fixed**:
   `findElements()` now checks first whether `commonParent`'s own range already fits the selection
   and returns `[commonParent]` directly in that case, instead of always decomposing into children.

**Both hypotheses from "Root cause" are resolved as false**, confirmed via a real
`kotlin-stdlib`-backed session test (`KaExtractFunctionTest.testCompute_withRealSession_multiParam*`):
- The *default* (innermost-block) destination scope correctly returns **no parameters** for
  `println(a + b)` — this is correct Kotlin semantics (a local function nested in the same block
  captures `a`/`b` as closures), not a bug.
- The *top-level* destination scope (the `outer()` example from this doc's Goal section) now
  correctly returns `parameters = [a, b]` after the two fixes above, using the **existing**,
  already-ported analysis pipeline — no `Generator`/generation-engine port was needed to fix this.

**Consequence for the rest of this plan:** the reported bug is fixed without Phase 1–3. Phases
1–4 (porting `Generator.generateDeclaration` to replace the hand-rolled text generation in
`KotlinExtractFunctionPlugin.performChange`) remain worthwhile as a quality/robustness
improvement — the hand-rolled generator still does its own indentation/formatting instead of
using IDEA's real `CodeStyleManager`-based reformatting, and doesn't handle `shortenReferences`,
duplicate detection, or other cases the real engine covers — but they are no longer gating a
known-broken feature. Proceeding with Phase 1 per user decision (fix bugs first, then still do
the full port).

## File inventory (for Phase 1 includes)
Generation files to add to `KotlinRefactoring/pom.xml` (paths under
`submodules/IntellijCommunity/plugins/kotlin/refactorings/`):
- `kotlin.refactorings.common/.../extractionEngine/ExtractFunctionGenerator.kt`
- `kotlin.refactorings.common/.../extractionEngine/IExtractionEngine.kt`
- `kotlin.refactorings.common/.../extractionEngine/PostInsertDeclarationCallback.kt`
- `kotlin.refactorings.common/.../extractionEngine/duplicateUtil.kt`
- `kotlin.refactorings.k2/.../introduce/extractionEngine/Generator.kt`
- `kotlin.refactorings.k2/.../introduce/extractionEngine/extractionEngineUtil.kt`
- `kotlin.refactorings.k2/.../introduce/extractionEngine/ExtractionEngineHelper.kt`
(+ whatever these transitively require, added iteratively; UI/action files excluded.)
