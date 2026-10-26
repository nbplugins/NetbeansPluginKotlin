# Kotlin Refactoring Parity Backlog

This backlog turns the residual gaps recorded in [refactoring-coverage.md](refactoring-coverage.md)
into ordered, executable work packages. The coverage document remains the **status source of
truth**; this file only describes what is left and how to close it.

Baseline: 0.11.x (entries below were verified against `main` at 0.11.59).

## Why this file exists

`docs/refactoring-coverage.md` records 30 Kotlin refactoring families as
**0 complete / 30 partial / 0 absent**. Every milestone named in its `Target` column
(F0–F8) has been delivered, but delivery of a milestone did not make the rows complete:
each family still has a documented parity gap. Those gaps live as prose inside a wide
table, so they are hard to schedule or verify.

This file groups them into work packages, names the real extension points in the current
code, and defines what "complete" means for a row.

## Baseline

| Item | Value |
|---|---|
| Tracked refactoring families | 30 |
| Complete | 0 |
| Partial | 30 |
| Absent | 0 |
| Milestones delivered | F0–F8 (F6/F7 only in their portable subset) |
| Matrix guard | `Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/RefactoringCoverageBaselineTest.kt` |

Scope: Kotlin-specific K2 refactorings ported or adapted from IntelliJ IDEA. Generic
Java/NetBeans refactorings and IDEA-only UI telemetry are out of scope.

## Definition of Done for a row

A row may move from `partial` to `complete` only when all of the following hold:

1. The refactoring is reachable from NetBeans (action, menu entry, or keybinding).
2. Behavior tests and the applicable conflict tests exist.
3. Multi-file changes use transactional undo, and **Undo Last Refactoring** restores every
   touched file/path.
4. No standalone limitation remains unrecorded. Permanent deviations are written into the
   matrix gap text as deliberate, not left implied.
5. The compatibility fixture is strengthened before the status changes — the rule stated at
   the end of `docs/refactoring-coverage.md` ("When a future port changes a row from Partial
   to Complete, it must add or strengthen the relevant compatibility fixture").

### Promotion procedure

1. Strengthen the family's test fixture under `Nbm/src/test/resources/projForTest/src/`.
2. Change `status=partial` to `status=complete` in the row's HTML
   `refactoring-coverage:` record.
3. Rewrite the row's "Current coverage and material gap" cell.
4. Update `**Baseline counts:** …` in `docs/refactoring-coverage.md`.
5. Update the hard-coded totals and status assertions in `RefactoringCoverageBaselineTest`
   (it currently asserts `30` records, `30` partial, `0` absent, and the exact totals line).
6. Run `mvn test -pl Nbm -Dtest=RefactoringCoverageBaselineTest,<FamilyTest>`.
7. Add a CHANGELOG bullet only if the change is user-visible.

## Work packages

Each package lists the rows it serves. A row appears in several packages when it needs more
than one kind of work; it reaches `complete` only when all its packages are done.

### P0 — Scenario inventory for vague gaps

Some rows say only "broader IDEA scenarios remain". These cannot be estimated or verified
until the missing scenarios are enumerated.

- Rows: `change-signature`, `extract-function`, `introduce-variable`,
  `introduce-constant`, `introduce-import-alias`.
- Work: walk the IDEA K2 sources/test data for each family, list concrete unsupported
  scenarios, and replace the vague phrase in the matrix with the enumerated list.
- Exit: every affected row names its remaining scenarios explicitly, so later packages can
  target them.

### P1 — Cross-language and non-code usage search

Usage search is Kotlin-PSI-only everywhere. The standalone K2 session loads `.java` sources
into its file map, but installs a no-op `PsiSearchHelper` and exposes only `KtFile` through
`getKtFileForPath`, so no consumer can see Java references.

- Rows served: `rename`, `safe-delete`, `change-signature`, `inline-variable-property`,
  `inline-type-alias`, `inline-anonymous-function`, `introduce-import-alias`, `move-file`,
  `change-package`, `rename-file-package-directory`, `move-nested-member-method`.
- Current extension points:
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/resolve/KotlinAnalysisAPISession.kt`
    — `installNoOpPsiSearchHelper`, `getKtFileForPath`, source scan.
  - `KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaRenameComputer.kt`
    — `searchFileForUsages`, `collectOverrideTargets`.
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/navigation/KaFindUsagesComputer.kt`
    — `searchFileForUsages`.
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KotlinMoveUsageSearchServiceImpl.kt`
    — `findUsages`, `findImportingFiles`.
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KotlinChangeSignatureUsageSearchServiceImpl.kt`
    — `findUsages`, `findOverridings`, `findConstructorDelegationCallers`.
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KotlinStandaloneInheritorSearch.kt`
    — `search`, `collectDirectInheritors`.
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/projectsextensions/KotlinProjectHelper.kt`
    — `getJavaFilesByProject` (exists, not used for usage search).
- Work:
  1. Add a Java PSI accessor (or a `PsiFile` lookup beside `getKtFileForPath`) that returns
     the already-loaded `.java` files.
  2. Add a Java reference scanner: `PsiJavaFile` element walk resolving references to the
     target Kotlin declaration, plus Kotlin→Java reference resolution for the reverse
     direction.
  3. Widen every search implementation above to accept Java results, preserving the existing
     Kotlin ordering and deduplication.
  4. Add non-code occurrence support (comments, string literals, text occurrences) where IDEA
     does it, with an explicit text-scan fallback where no index exists.
  5. Add an index-only fallback decision: document which index-backed cases are permanently
     out of scope rather than silently missing.
- Tests: mixed Java+Kotlin fixtures. The test project and session already load `.java`
  files (`projForTest/src/diagnostics/SomeClass.java`,
  `projForTest/src/navigation/JavaClass.java`), but `KotlinTestCase` never exposes them to
  refactoring tests. Add a small helper that opens a Java `PsiFile` from the session file map
  and asserts the Java file text after the refactoring.
- Exit: a Kotlin declaration referenced from Java and a Java declaration referenced from
  Kotlin are both found and updated by rename/move/change-signature/inline, with undo.

### P2 — Complete F7: rename and safe delete

- Rows: `rename`, `safe-delete`, `rename-file-package-directory`.
- Current limitation:
  - `safe-delete` only counts Kotlin usages of a `KtNamedDeclaration`; it has no supertype,
    type/value-argument, override-cascade, cascade-delete, or Java checks.
  - `rename` has Java declaration renames but does not scan Java call sites; automatic
    renamers beyond the Kotlin override cascade are missing.
  - file rename does not update Java references to the file facade.
- Current extension points:
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaSafeDeleteComputer.kt`
    and `KotlinSafeDeletePlugin.kt`.
  - `Nbm/src/main/kotlin/org/jetbrains/kotlin/refactorings/rename/KotlinRenameRefactoring.kt`
    and `KaRenameComputer.kt`.
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KotlinRenamePlugin.kt`.
- Work:
  1. Safe delete: supertype usages, type and value-argument usages, override/supertype
     cascade, cascade deletion of dependent declarations, Java usages (depends on P1).
  2. Rename: Java call-site rename (depends on P1), automatic renamers (accessors,
     constructors, file facades, related declarations) and their conflict checks.
  3. File rename: update Java references to the `FooKt` facade and `@JvmName` facades;
     keep the current physical rename + package-directory behavior.
- Tests: safe-delete with a supertype/type-argument reference; rename with a Java caller;
  file rename with a Java `FooKt` caller; undo for each.
- Exit: the matrix gap text for the three rows has no Java/automatic-renamer item left.

### P3 — Hierarchy and conflict parity

- Rows: `pull-members-up`, `push-members-down`, `extract-interface-superclass`.
- Current limitation:
  - Pull: direct supertypes only; the only conflict check is a same-name member in the
    target; undo uses a plugin-local snapshot.
  - Push: `conflicts()` returns `emptyList()` unconditionally; the real engine checks
    (visibility, signature clash, accidental override, super calls, external usages) live in
    `internal` copied code and are index-dependent; undo uses a plugin-local snapshot.
  - Extract Super: advanced constructor routing, generics, and full conflicts remain.
- Current extension points:
  - `KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaPullMembersUpComputer.kt`
    (`discover`, `checkConflicts`, `apply`) and `KaPushMembersDownComputer.kt`
    (`discover`, `conflicts`, `apply`).
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KotlinPullMembersUpPlugin.kt`
    and `KotlinPushMembersDownPlugin.kt` (plugin-local undo).
  - Copied engine: `pushDownConflictsUtils.kt` under
    `submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/.../pushDown/`.
  - `KaExtractSuperComputer.kt` and the copied `K2ExtractSuperRefactoring`.
- Work:
  1. Make the engine conflict checks callable from the NetBeans layer (widen visibility the
     same way `KotlinCodeInsightImpl` widens IDEA classes) and return them through
     `KaPushMembersDownComputer.conflicts`.
  2. Add visibility, accidental-override, parameter/signature clash, and Java-hierarchy
     checks to Pull; include Java supertypes (depends on P1's Java inheritor search).
  3. Present conflicts in the NetBeans refactoring UI before mutation.
  4. Migrate Pull/Push undo from plugin-local snapshots to `KotlinRefactoringTransaction`
     so undo behavior is uniform with the other multi-file refactorings.
  5. Extract Super: advanced constructor routing, full generic/type-parameter cases, and the
     same conflict checks.
- Tests: pull/push into a target with a signature clash and an accidental override; Java
  supertype; Extract Super with a generic supertype and constructor arguments; multi-file
  undo.
- Exit: no row in this package has an unrecorded conflict-check gap.

### P4 — Advanced Extract/Introduce scenarios

- Rows: `extract-function`, `introduce-variable`, `introduce-constant`,
  `introduce-property`, `introduce-type-alias`, `introduce-parameter`,
  `introduce-functional-parameter`, `introduce-import-alias`, `change-signature`
  (after P0).
- Current limitation (from the matrix):
  - Extract Function: control flow, smart casts, receivers, duplicate matrices.
  - Introduce Variable: advanced contexts (enumerated by P0).
  - Introduce Constant: broader IDEA contexts.
  - Introduce Property: delegated properties and complex receivers.
  - Introduce Type Alias: star projections, aliases requiring local type parameters,
    class-body targets, expressions, project-wide duplicate search.
  - Introduce Parameter: context parameters, adding an absent primary constructor.
  - Introduce Functional Parameter: multi-statement and idiomatic lambda cases.
  - Introduce Import Alias: import edge cases (enumerated by P0).
  - Change Signature: remaining IDEA scenarios (enumerated by P0).
- Current extension points:
  - `KaExtractFunctionComputer.kt`, `KaIntroduceVariableComputer.kt`,
    `KaIntroduceConstantComputer.kt`, `KaIntroducePropertyComputer.kt`,
    `KaIntroduceTypeAliasComputer.kt`, `KaIntroduceParameterComputer.kt`,
    `KaIntroduceFunctionalParameterComputer.kt`, `KaIntroduceImportAliasComputer.kt`,
    `KaChangeSignatureComputer.kt` (all under
    `KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/`).
  - The copied IDEA engines already used by these adapters.
- Work: per row, implement the engine paths that exist upstream but are not yet wired, or
  replace the hand-written approximation with the copied engine path. Keep the NetBeans
  dialog/preview/transaction ownership unchanged.
- Tests: one fixture per newly supported scenario, plus a negative fixture where the
  refactoring must be rejected before mutation.
- Exit: each row's matrix gap is reduced to scenarios that are explicitly out of scope.

### P5 — Inline family parity

- Rows: `inline-variable-property`, `inline-function`, `inline-type-alias`,
  `inline-anonymous-function`.
- Current limitation:
  - Variable: receiver/member/accessor, comments, Java and index-backed references.
  - Function: callable references, recursion, Java cases.
  - Type alias: Java/non-code/index-only references.
  - Anonymous function: non-invoked functions, Java/non-code/index-only references, wider
    IDEA invocation forms.
- Current extension points:
  - `KaInlineVariableComputer.kt`, `KaInlineFunctionComputer.kt`,
    `KaInlineTypeAliasComputer.kt`, `KaInlineAnonymousFunctionComputer.kt`.
- Work: receiver/member/accessor inlining for variables; callable-reference and recursion
  handling for functions; Java/non-code references (depends on P1); wider lambda/anonymous
  invocation forms.
- Tests: callable reference to the inlined function; recursive function rejection or
  handling; Java caller; non-code reference; undo across all touched files.
- Exit: only documented out-of-scope forms remain.

### P6 — Scope expansions

- Rows: `move-declaration`, `copy-declaration`, `move-nested-member-method`, `move-file`,
  `change-package`.
- Current limitation:
  - Move/Copy Declaration: top-level declarations only.
  - Move Nested Member: instance members, constructors, enum entries, aliases, and
    unvalidated external qualified-use rewrites are unavailable.
  - Move File: generic non-Kotlin content is not moved; Java/comment/text/index-only
    references are not updated.
  - Change Package / Move File: full IDEA project-model conflict behavior and arbitrary
    multi-selection are unavailable.
- Current extension points:
  - `KaMoveDeclarationComputer.kt`, `KaCopyDeclarationComputer.kt`,
    `KaMoveNestedMemberComputer.kt`, `KaMoveFileComputer.kt`,
    `KaChangePackageComputer.kt`.
  - `Nbm/.../refactoring/KotlinMoveNestedMemberPlugin.kt` (validation boundary).
- Work:
  1. Nested declarations for Move/Copy (target selection and retargeting).
  2. Instance members, constructors, enum entries, and alias imports for Move Nested Member;
     validate external qualified-use rewrites before applying.
  3. Non-Kotlin content movement for Move File (depends on P1 for Java references).
  4. Project-model conflict checks and multi-selection for Move File / Change Package.
- Tests: nested declaration copy/move; instance member move; non-Kotlin file move; conflict
  rejection; undo.
- Exit: the remaining scope is explicitly listed as out of scope in the matrix.

### P7 — Structural conversions (F8) completion

- Rows: all six `call-to-binary-operator`, `get-or-set-to-indexing`,
  `contains-to-in-operator`, `invoke-to-direct-call`, `call-to-unary-operator`,
  `remove-empty-lambda-parentheses`.
- Current limitation: only the six forward conversions exist, driven from a caret hint.
  The reverse directions are hand-written approximations
  (`KaOperatorToFunctionIntention.kt`, `KaConvertLambdaToReferenceIntention.kt`).
- Current extension points:
  - `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/hints/intentions/KaStructuralConversionHints.kt`
    — `hints()` mapping table.
  - `KaIdeaIntentionHint.kt` and `KaIdeaInspectionHint.kt` reflective adapters.
  - `KotlinCodeInsightImpl/pom.xml` — `maven-resources-plugin` copy executions and the
    `gmavenplus-plugin` visibility patch that widens `internal` classes.
- Work:
  1. Add a `copy-intentions-shared` execution for IDEA's `OperatorToFunctionIntention` and
     add it to the visibility patch list.
  2. Copy IDEA's `ConvertLambdaToReferenceIntention` plus its helpers
     (`ConvertLambdaToReferenceUtils`, `InsertExplicitTypeArgumentsUtils`,
     `RedundantExplicitTypeArgumentsUtil`) and add them to the visibility patch.
  3. Map both in `KaStructuralConversionHints` and retire the hand-written fallbacks.
  4. Cover the widened conversion forms with caret-hint tests.
- Tests: `KaIntentionsTest` cases for both reverse conversions, including type arguments and
  overloaded references.
- Exit: the six rows have IDEA-backed forward and reverse directions; any remaining
  caret-context limitation is recorded.

## Suggested sequencing

| Milestone | Work packages | Expected rows reaching `complete` | Depends on |
|---|---|---|---|
| **F9** | P1 | Rows whose only gap is cross-language/non-code search (e.g. `inline-type-alias`) | — |
| **F10** | P2 | `rename`, `safe-delete`, `rename-file-package-directory` | F9 |
| **F11** | P3 | `pull-members-up`, `push-members-down`, `extract-interface-superclass` | F9 |
| **F12** | P0, P4, P5 | Extract/Introduce/Inline families | F9 (for Java/index parts) |
| **F13** | P6 | Move/Copy/Nested/Move-file/Change-package | F9 |
| **F14** | P7 | Six F8 conversion rows | — |

Rationale: P1 is vertical — it unblocks P2 and large parts of P4–P6. P7 is independent and
can run in parallel with any package.

## Per-row ledger

Paths are abbreviated: `KR` = `KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring`,
`NB` = `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm`, `T` =
`Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm`.

| ID | Matrix milestone | Remaining gap | Adapter | Test | Packages |
|---|---|---|---|---|---|
| `rename` | F7 | Java call sites, automatic renamers | `KR/KaRenameComputer.kt`, `NB/refactoring/KotlinRenamePlugin.kt` | `T/refactoring/KaRenameTest.kt` | P1, P2 |
| `safe-delete` | F7 | Supertype/type-argument checks, Java bridge | `NB/refactoring/KaSafeDeleteComputer.kt` | `T/refactoring/KaSafeDeleteTest.kt` | P1, P2 |
| `change-signature` | F1 | Broader IDEA scenarios (enumerate first) | `KR/KaChangeSignatureComputer.kt` | `T/refactoring/KaChangeSignatureTest.kt` | P0, P1, P4 |
| `inline-variable-property` | F3 | Receiver/member/accessor, comments, Java, index-only | `KR/KaInlineVariableComputer.kt` | `T/refactoring/KaInlineVariableTest.kt` | P1, P5 |
| `inline-function` | F3 | Callable references, recursion, Java | `KR/KaInlineFunctionComputer.kt` | `T/refactoring/KaInlineFunctionTest.kt` | P1, P5 |
| `inline-type-alias` | F5 | Java/non-code/index-only references | `KR/KaInlineTypeAliasComputer.kt` | `T/refactoring/KaInlineTypeAliasTest.kt` | P1, P5 |
| `inline-anonymous-function` | F5 | Non-invoked functions, Java/non-code/index, wider forms | `KR/KaInlineAnonymousFunctionComputer.kt` | `T/refactoring/KaInlineAnonymousFunctionTest.kt` | P1, P5 |
| `extract-function` | F6 | Control flow, smart cast, receiver, duplicates | `KR/KaExtractFunctionComputer.kt` | `T/refactoring/KaExtractFunctionTest.kt` | P0, P4 |
| `introduce-variable` | F6 | Advanced contexts (enumerate first) | `KR/KaIntroduceVariableComputer.kt` | `T/refactoring/KaIntroduceVariableTest.kt` | P0, P4 |
| `introduce-constant` | F6 | Broader IDEA contexts | `KR/KaIntroduceConstantComputer.kt` | `T/refactoring/KaIntroduceConstantTest.kt` | P0, P4 |
| `introduce-property` | F6 | Delegated properties, complex receivers | `KR/KaIntroducePropertyComputer.kt` | `T/refactoring/KaIntroducePropertyTest.kt` | P4 |
| `introduce-type-alias` | F3 | Star projections, local type params, class-body targets, expressions, project-wide duplicates | `KR/KaIntroduceTypeAliasComputer.kt` | `T/refactoring/KaIntroduceTypeAliasTest.kt` | P4 |
| `introduce-import-alias` | F6 | Import edge cases (enumerate first) | `KR/KaIntroduceImportAliasComputer.kt` | `T/refactoring/KaIntroduceImportAliasTest.kt` | P0, P1, P4 |
| `introduce-parameter` | F6 | Context parameters, absent primary constructor | `KR/KaIntroduceParameterComputer.kt` | `T/refactoring/KaIntroduceParameterTest.kt` | P4 |
| `introduce-functional-parameter` | F6 | Multi-statement, idiomatic lambda | `KR/KaIntroduceFunctionalParameterComputer.kt` | `T/refactoring/KaIntroduceFunctionalParameterTest.kt` | P4 |
| `move-declaration` | F1 | Nested declarations | `KR/KaMoveDeclarationComputer.kt` | `T/refactoring/KaMoveDeclarationTest.kt` | P6 |
| `copy-declaration` | F1 | Nested declarations, broader targets | `KR/KaCopyDeclarationComputer.kt` | `T/refactoring/KaCopyDeclarationTest.kt` | P6 |
| `extract-interface-superclass` | F6 | Advanced constructors, generics, conflicts | `KR/KaExtractSuperComputer.kt` | `T/refactoring/KaExtractSuperComputerTest.kt` | P3 |
| `pull-members-up` | F2 | Visibility, accidental override, Java, index | `KR/KaPullMembersUpComputer.kt` | `T/refactoring/KaPullMembersUpComputerTest.kt` | P3 |
| `push-members-down` | F2 | Conflict checks, visibility, accidental override, Java | `KR/KaPushMembersDownComputer.kt` | `T/refactoring/KaPushMembersDownComputerTest.kt` | P3 |
| `move-file` | F4 | Non-Kotlin content, Java/non-code/index refs, project model | `KR/KaMoveFileComputer.kt` | `T/refactoring/KaMoveFileComputerTest.kt` | P1, P6 |
| `change-package` | F4 | Java/non-code/index refs, project model, multi-selection | `KR/KaChangePackageComputer.kt` | `T/refactoring/KaChangePackageTest.kt` | P1, P6 |
| `move-nested-member-method` | F4 | Instance members, constructors, enum entries, aliases, qualified uses | `NB/refactoring/KotlinMoveNestedMemberPlugin.kt` | `T/refactoring/KaMoveNestedMemberComputerTest.kt` | P1, P6 |
| `rename-file-package-directory` | F7 | Java facade, automatic renamers, Java/non-code/index refs, Unicode/quoted names | `NB/refactoring/KotlinRenamePlugin.kt` | `T/refactoring/KotlinRefactoringTransactionTest.kt` | P1, P2 |
| `call-to-binary-operator` | F8 | Reverse direction, wider forms | `NB/hints/intentions/KaStructuralConversionHints.kt` | `T/hints/intentions/KaIntentionsTest.kt` | P7 |
| `get-or-set-to-indexing` | F8 | Reverse direction, wider forms | same | same | P7 |
| `contains-to-in-operator` | F8 | Reverse direction, wider forms | same | same | P7 |
| `invoke-to-direct-call` | F8 | Reverse direction, wider forms | same | same | P7 |
| `call-to-unary-operator` | F8 | Reverse direction, wider forms | same | same | P7 |
| `remove-empty-lambda-parentheses` | F8 | Reverse direction, wider forms | same | same | P7 |

## Non-goals and risks

- **Out of scope by design:** IDEA's writable VFS, command/progress/dialog/listener
  lifecycle, action-system entry points, telemetry, and index-backed searches that have no
  standalone equivalent. Record these explicitly in the matrix instead of over-claiming.
- **Standalone K2 limits:** all five usage-search implementations rely on full PSI scans;
  adding Java search must keep them deterministic and reasonably fast on large projects.
- **Two undo mechanisms:** multi-file refactorings use `KotlinRefactoringTransaction` or
  `HunkedRefactoringTransaction`, while Pull/Push still use plugin-local snapshots. New work
  must not add a third mechanism.
- **Session refresh:** refactorings that add/remove files or change paths must rebuild the
  standalone K2 session, as the file/package move family already does.
- **Mixed fixtures:** the test project loads `.java` files, but no refactoring-level
  mixed-language harness exists yet. P1 must add one before Java-bridge work can be tested.
- **Scenario drift:** each package should start with the P0-style inventory of the IDEA
  sources it targets, so "complete" does not silently mean "whatever we happened to test".

## Related documents

- [refactoring-coverage.md](refactoring-coverage.md) — status matrix and machine-checked
  records.
- [development-plan.md](development-plan.md) — long-term plan (Track E and the F milestones).
