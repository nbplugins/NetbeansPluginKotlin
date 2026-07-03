# E9 — Port IDEA refactoring engines directly (Extract Function first)

Status: **Phase 0–3 complete; Phase 4 tests/docs done, manual verification pending.**

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

**Revised (2026-07-02) after reading all 7 originally-listed files:** only 3 are actually
needed. `IExtractionEngine.kt`, `ExtractionEngineHelper.kt`, and `extractionEngineUtil.kt` are
pure UI-orchestration (dialogs, EDT commands, conflict-checking balloons) around a `run()`/
`doRefactor()` flow we never call — we already do analysis ourselves via
`ExtractionDataAnalyzer(...).performAnalysis()` in `KaExtractFunctionComputer`, and in Phase 2 we
will call `Generator.generateDeclaration(config, null)` directly, copying the 2-line
`allowAnalysisOnEdt { allowAnalysisFromWriteAction { ... } }` wrapper from
`ExtractionEngineHelper.generateDeclaration` without porting the class. `duplicateUtil.kt` is
already unneeded since `ExtractableCodeDescriptor.duplicates` is patched to `emptyList()`.

Generation files to add to `KotlinRefactoring/pom.xml` (paths under
`submodules/IntellijCommunity/plugins/kotlin/refactorings/`):
- `kotlin.refactorings.common/.../extractionEngine/ExtractFunctionGenerator.kt` — the real generator.
- `kotlin.refactorings.common/.../extractionEngine/PostInsertDeclarationCallback.kt` — trivial EP
  interface referenced by `ExtractFunctionGenerator`.
- `kotlin.refactorings.k2/.../introduce/extractionEngine/Generator.kt` — concrete
  `object Generator : ExtractFunctionGenerator<KaType, ExtractionResult>()`.
(+ whatever these transitively require, added iteratively; UI/action files excluded.)

## Phase 1 complete (2026-07-02)

Built successfully. In addition to the 3 files above, one more submodule file was ported directly
via `maven-resources` (lightweight, no Editor/UI deps):
- `plugins/kotlin/base/analysis/src/.../refactoring/introduce/ExtractableSubstringInfo.kt` — shared
  (non-K1-coupled) `extractableSubstringInfo`/`substringContextOrThis` definitions referenced
  directly by `ExtractFunctionGenerator.kt`. (`ExtractionData.kt`'s own `substringInfo` stays
  stubbed to `null` — NB never extracts string-template substrings from the UI — so the
  `replaceWith()` path this enables is never actually exercised, but the types must still resolve
  at compile time.)

Five more small extension functions used by `ExtractFunctionGenerator.kt` lived in files with heavy
IDE-UI dependencies mixed in (`Editor`, `CommonRefactoringUtil`, dialog helpers), so — following the
project's established convention (see the existing `kotlinCommonRefactoringUtil.kt` /
`org/jetbrains/kotlin/idea/k2/refactoring/utils.kt` local stubs) — they were copied verbatim into
local stub files instead of pulling in the whole heavy file:
- `KotlinRefactoring/src/main/kotlin/org/jetbrains/kotlin/idea/refactoring/kotlinCommonRefactoringUtil.kt`
  — added `KtBlockExpression.addElement()` (copied from IDEA's `kotlinCommonRefactoringUtil.kt`).
- `KotlinRefactoring/src/main/kotlin/org/jetbrains/kotlin/idea/refactoring/introduce/introduceUtilsStub.kt`
  (new) — copied `removeTemplateEntryBracesIfPossible`, `mustBeParenthesizedInInitializerPosition`,
  `getContainingLambdaOutsideParentheses`, `getGeneratedBody`, and
  `ExtractableSubstringInfo.replaceWith()` verbatim from IDEA's `introduceUtils.kt`.

Two minor import corrections needed versus the original source (era-253/compiler-jar packaging
differences, not logic changes): `PsiChildRange` resolves under
`org.jetbrains.kotlin.psi.psiUtil` (not `org.jetbrains.kotlin.psi`), and `getElementTextWithContext`
(`org.jetbrains.kotlin.utils.PsiUtilsKt`) is a plain top-level function taking an explicit
`PsiElement` argument in this compiler build, not an extension — call site adjusted to
`getElementTextWithContext(this)`.

`mvn install` (full reactor) and `mvn test` both pass with no regressions.

## Phase 2 complete (2026-07-02)

Added `KaExtractFunctionComputer.generate(chosenName, targetSiblingOffset)` — a new, additive API
alongside the existing (unchanged) `compute()`. Design departs from the plan's original
"writable copy + diff" sketch in two ways, both confirmed safe by precedent already in this
codebase:

- **No throwaway copy.** `KotlinInlineVariablePlugin.kt` already mutates the *live* session
  `KtFile` directly (via `CodeInliner`) and pushes the result back to the NetBeans `Document` —
  proven safe in this standalone container. `generate()` does the same: builds an
  `ExtractionGeneratorConfiguration` from the analyzed `ExtractableCodeDescriptor` (renamed via
  `.copy(suggestedNames = listOf(chosenName))`) and calls `Generator.generateDeclaration(config,
  null)` directly on `ktFile`, wrapped in `allowAnalysisOnEdt { allowAnalysisFromWriteAction { } }`
  (mirroring upstream's own `ExtractionEngineHelper.generateDeclaration`).
- **Single-range diff, not whole-document replace.** Unlike Inline Variable's coarser
  whole-document replace (an accepted limitation there), Extract Function already has the
  caret-preserving-undo fix (`joinCaretRestoreOnUndo` + `MinimalDocumentEdits`). `generate()`
  preserves that: a small `computeMinimalDiff` (longest-common-prefix/suffix trim) reduces the
  pre/post-mutation text difference to one `TextRange` + replacement string, returned via
  `KaExtractFunctionEdit` for Phase 3 to apply through the existing `MinimalDocumentEdits.apply`.

`compute()` deliberately stays non-mutating (used by `prepare()`/`KotlinExtractFunctionAction` to
populate the dialog before the user commits) — only `generate()`, called once at apply time,
touches PSI. Both share analysis via a new private `analyzeSelection()` helper.

**Two standalone-session gaps hit and fixed, both required for `Generator.generateDeclaration` to
run at all:**

1. **Missing exception class at runtime.** `ExtractFunctionGenerator.buildSignature` references
   `BaseRefactoringProcessor.ConflictsInTestsException` in its exception table; our runtime stub
   (`Nbm/src/main/java/com/intellij/refactoring/BaseRefactoringProcessor.java`, added for Inline
   Variable) didn't declare it, so the JVM threw `NoClassDefFoundError` the moment the method was
   invoked (regardless of whether the conflict branch was actually taken). Fixed by adding the
   nested class (matching upstream's constructor signature) to the stub.
2. **Missing extension point.** `ExtractFunctionGenerator.insertDeclaration` calls
   `PostInsertDeclarationCallback.EP_NAME.forEachExtensionSafe { }`, which requires the EP to be
   *registered* even with zero implementations. Fixed by registering it (empty) in
   `KotlinAnalysisAPISession.registerHighlightInfoFilterEP()`, alongside the other zero-extension
   EPs already registered there for the same reason.

**Third, deeper gap — brand-new PSI not visible to FIR immediately:** `ShortenReferencesFacility
.getInstance().shorten(declaration)` (called by the generator right after inserting the new
function) threw `KotlinIllegalArgumentExceptionWithAttachments: No fir element was found for
KtNamedFunction`. In a real IDE, PSI-change listeners incrementally rebuild the FIR
`FileStructure` model so a freshly-inserted declaration resolves immediately; standalone/
`MockProject` mode has no such listener. Root-caused via a background research pass through
`analysis-low-level-api-fir` (confirmed: the raw `FirFile` is cached once per session in
`LLFirFileBuilder`, and `FileStructure.invalidateElement()` only drops one cache entry — it can't
help for a declaration that never had one). The two real invalidation entry points
(`LLFirDeclarationModificationService.elementModified` / `KaModule
.publishModuleOutOfBlockModificationEvent`) publish to a message bus with no subscriber in the
standalone builder — a dead end, not a fix.

Fix: `KotlinSymbolBasedShortenReferencesFacility` (our registered `ShortenReferencesFacility`
implementation) now clears the same two FIR-session caches
`KotlinAnalysisAPISession.updateFileContent` already clears for the identical reason
(`LLFirSessionCache...sourceCache.clear()` + `KaSessionProvider.clearCaches()`), immediately
before delegating to the real shortening logic — not a "best-effort swallow" as first considered,
a real fix that makes shortening work correctly for freshly-inserted declarations. Required adding
`low-level-api-fir-for-ide` as a `provided` dependency to `KotlinRefactoring/pom.xml` (already a
dependency of `Nbm`, supplied at runtime from there).

Caret positioning after generation is derived syntactically (`PsiTreeUtil.findChildrenOfType` +
callee-text match), not via `ReferencesSearch`/FIR resolution — deliberately, to avoid depending on
resolving elements that were just mutated in the same call.

New test: `KaExtractFunctionTest.testGenerate_withRealSession_multiParam_topLevelScope` exercises
`generate()` end-to-end against the `outer()` fixture and asserts the real generated text contains
both `extracted(a, b)` (call site) and `fun extracted(a: Int, b: Int)` (signature) — proving the
ported generator, not hand-rolled string templates, now produces this output. Full `mvn test`
(all modules) passes with no regressions.

## Phase 3 complete (2026-07-02)

`KotlinExtractFunctionPlugin.performChange` no longer builds function/call text by hand. It now:
1. Calls `computer.generate(chosenName, targetSiblingOffset)` (chosenName falls back to
   `"extractedFunction"` if the dialog field was left blank, same as before).
2. Applies the returned `KaExtractFunctionEdit` as a single `MinimalDocumentEdits.apply` call
   (unchanged `joinCaretRestoreOnUndo` wrapping — caret-safe undo behavior preserved) and reformats
   just the edited range.
3. Moves the caret to `edit.caretOffset` directly — no coordinate translation needed, since
   `edit.caretOffset` is already in post-edit document coordinates (the single `MinimalDocumentEdits`
   call reproduces the engine's post-mutation text exactly, verified by
   `testGenerate_withRealSession_multiParam_topLevelScope`'s reconstruction assertion in Phase 2).

Along the way, fixed two bugs introduced during Phase 2's own refactor: both `compute()` and
`generate()` called the shared `analyzeSelection()` helper *outside* their `try`/`catch`, so an
exception thrown during analysis (e.g. from `ExtractionDataAnalyzer.performAnalysis()`) would
propagate uncaught instead of surfacing as `Outcome.Error`/`GenerateOutcome.Error` like the
original pre-Phase-2 code guaranteed. Moved the call inside each `try` block.

Deliberately did **not** trim the now-unused fields of `KaExtractFunctionResult` (`parameters`,
`returnTypeText`, `selectionText`, `insertOffset`, `selectionRange`) even though
`KotlinExtractFunctionPlugin` no longer reads them — several existing tests
(`testCompute_withRealSession_multiParam*`, `testWithParam_detectsParameter`, etc.) use these
fields as direct assertions on the *analysis* engine's correctness (parameter inference per
scope), independent of generation; trimming would have meant rewriting that coverage for no
functional gain.

Renamed `testApply_withRealSession_simpleExpr` → `testGenerate_withRealSession_simpleExpr`: the
old version manually replicated the (now-deleted) hand-rolled text-building logic using
`compute()`'s result fields — testing code that no longer exists in production. Rewritten to call
`generate()` directly and assert on the real mutated text.

Full `mvn test` (all modules) and `mvn clean package -DskipTests` (produces the `.nbm`) both pass.

## Phase 4 progress (2026-07-02)

Added 3 new fixtures + tests exercising `generate()` against cases beyond the `outer()` example:
- `returnValue/` — `val r = a + b`, extracted to top-level scope: verifies a non-Unit return
  (`fun extracted(a: Int, b: Int): Int`) and the call-site assignment (`val r = extracted(a, b)`).
- `multiStatement/` — two `println` statements selected together, default scope: verifies both
  statements land in the body and the whole selection collapses to one call.
- `classMember/` — extraction to a class-body (member) scope via `collectScopeCandidates()` (not
  local, not top-level): verifies the new function is inserted as a `Calculator` member, not at
  file scope.

Updated `docs/e9-refactoring-undo-caret-test-plan.md` section 6 (Extract Function) to describe
both the default (innermost, no-parameters) and top-level (parameters, `private` visibility)
scope cases, replacing the old single-scenario description.

Full `mvn test` (all modules, 14 KaExtractFunctionTest cases) and `mvn clean package -DskipTests`
pass. Manual verification in a running NetBeans instance is the remaining step before commit+PR.
