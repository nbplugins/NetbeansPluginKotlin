# Kotlin Refactoring Coverage

<!--
Refactoring coverage records are verified by
`RefactoringCoverageBaselineTest`. Paths are repository-relative.

Format:
refactoring-coverage: id=<id>; status=<complete|partial|absent>; idea=<path>;
netbeans=<path>; test=<path>; milestone=<F0-F8>
-->
<!-- refactoring-coverage: id=rename; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/rename.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/rename/K2RenameRefactoringSupport.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaRenameComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaRenameTest.kt; milestone=F7 -->
<!-- refactoring-coverage: id=safe-delete; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/safeDelete/KotlinFirSafeDeleteProcessor.kt; netbeans=Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaSafeDeleteComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaSafeDeleteTest.kt; milestone=F7 -->
<!-- refactoring-coverage: id=change-signature; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/changeSignature/KotlinChangeSignatureProcessor.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaChangeSignatureComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaChangeSignatureTest.kt; milestone=F1 -->
<!-- refactoring-coverage: id=inline-variable-property; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/inline/KotlinInlinePropertyProcessor.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaInlineVariableComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaInlineVariableTest.kt; milestone=F3 -->
<!-- refactoring-coverage: id=inline-function; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/inline/KotlinInlineFunctionProcessor.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaInlineFunctionComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaInlineFunctionTest.kt; milestone=F3 -->
<!-- refactoring-coverage: id=inline-type-alias; status=absent; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/inline/KotlinInlineTypeAliasProcessor.kt; netbeans=none; test=none; milestone=F5 -->
<!-- refactoring-coverage: id=inline-anonymous-function; status=absent; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/inline/KotlinInlineAnonymousFunctionProcessor.kt; netbeans=none; test=none; milestone=F5 -->
<!-- refactoring-coverage: id=extract-function; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduce/extractionEngine/Generator.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaExtractFunctionComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaExtractFunctionTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=introduce-variable; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduce/introduceVariable/K2IntroduceVariableHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroduceVariableComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroduceVariableTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=introduce-constant; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduceConstant/KotlinIntroduceConstantHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroduceConstantComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroduceConstantTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=introduce-property; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduceProperty/KotlinIntroducePropertyHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroducePropertyComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroducePropertyTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=introduce-type-alias; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduceTypeAlias/IntroduceTypeAliasHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroduceTypeAliasComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroduceTypeAliasTest.kt; milestone=F3 -->
<!-- refactoring-coverage: id=introduce-import-alias; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduceImportAlias/KotlinIntroduceImportAliasHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroduceImportAliasComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroduceImportAliasTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=introduce-parameter; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduceParameter/KotlinFirIntroduceParameterHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroduceParameterComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroduceParameterTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=introduce-functional-parameter; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/introduceParameter/KotlinFirIntroduceParameterHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaIntroduceFunctionalParameterComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaIntroduceFunctionalParameterTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=move-declaration; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.move.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/move/processor/K2MoveDeclarationsRefactoringProcessor.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaMoveDeclarationComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaMoveDeclarationTest.kt; milestone=F1 -->
<!-- refactoring-coverage: id=copy-declaration; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/copy/CopyKotlinDeclarationsHandler.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaCopyDeclarationComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaCopyDeclarationTest.kt; milestone=F1 -->
<!-- refactoring-coverage: id=extract-interface-superclass; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/extractClass/K2ExtractSuperRefactoring.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaExtractSuperComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaExtractSuperComputerTest.kt; milestone=F6 -->
<!-- refactoring-coverage: id=pull-members-up; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/pullUp/K2PullUpHelper.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaPullMembersUpComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaPullMembersUpComputerTest.kt; milestone=F2 -->
<!-- refactoring-coverage: id=push-members-down; status=partial; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/pushDown/K2PushDownProcessor.kt; netbeans=KotlinRefactoring/src/main/kotlin/io/github/nbplugins/kotlin/refactoring/KaPushMembersDownComputer.kt; test=Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/refactoring/KaPushMembersDownComputerTest.kt; milestone=F2 -->
<!-- refactoring-coverage: id=move-file; status=absent; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.move.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/move/processor/K2MoveFilesOrDirectoriesRefactoringProcessor.kt; netbeans=none; test=none; milestone=F4 -->
<!-- refactoring-coverage: id=change-package; status=absent; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.move.k2/src/org/jetbrains/kotlin/idea/k2/refactoring/move/processor/K2ChangePackageRefactoringProcessor.kt; netbeans=none; test=none; milestone=F4 -->
<!-- refactoring-coverage: id=move-nested-member-method; status=absent; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.common/src/org/jetbrains/kotlin/idea/refactoring/move/MoveKotlinMemberHandler.kt; netbeans=none; test=none; milestone=F4 -->
<!-- refactoring-coverage: id=rename-file-package-directory; status=absent; idea=submodules/IntellijCommunity/plugins/kotlin/refactorings/kotlin.refactorings.common/src/org/jetbrains/kotlin/idea/refactoring/rename/KotlinRenameRefactoringSupport.kt; netbeans=none; test=none; milestone=F7 -->

## Purpose and scope

This matrix measures **Kotlin-specific refactorings** available in the checked-out IntelliJ Community K2 sources against the NetBeans Kotlin plugin. It excludes general Java/NetBeans refactorings and IDEA-only UI telemetry. A row describes a user-facing refactoring family, not every implementation class.

The corresponding record comments above are deliberately machine-readable. `RefactoringCoverageBaselineTest` verifies that every referenced source and test path exists, so the matrix cannot silently drift after a port, rename, or submodule update.

## Status criteria

| Status | Meaning |
|---|---|
| **Complete** | Reachable from NetBeans, covered by behavior and applicable conflict tests, supports transactional multi-file undo where needed, and has no unrecorded standalone limitation. |
| **Partial** | Reachable, but lacks IDEA scenarios, project-model/index support, complete conflict detection, or uniform multi-file undo/redo. |
| **Absent** | IDEA exposes the Kotlin refactoring family but the NetBeans plugin has no corresponding command. |
| **Not applicable** | IDEA-only integration which is not a Kotlin refactoring target for this plugin. No such rows are currently tracked. |

**Baseline counts:** 0 complete, 18 partial, 6 absent, 24 total.

## Current NetBeans refactorings

| ID | IDEA K2 family | NetBeans command | Status | Current coverage and material gap | Target |
|---|---|---|---|---|---|
| `rename` | Rename | Rename (`Alt+Shift+R`) | Partial | K2 declaration/reference rename and override cascade are covered; file/package/directory renames and IDEA automatic renamers remain. | F7 |
| `safe-delete` | Safe Delete | Safe Delete (`Alt+Delete`) | Partial | Declaration usage check is available; IDEA supertype, type/value-argument, and Java-bridge cases remain. | F7 |
| `change-signature` | Change Signature | `Ctrl+F6` | Partial | K2 signature engine handles calls, overrides, constructors, destructuring and operators; atomic rollback and a shared undo transaction are still missing. | F1 |
| `inline-variable-property` | Inline property | Inline (`Ctrl+Alt+N`) | Partial | IDEA code inliner backs `val`/property scenarios; broader `var`, receiver, comment and Java cases remain. | F3 |
| `inline-function` | Inline function | Inline (`Ctrl+Alt+N`) | Partial | K2 engine supports named functions; complex callable-reference, recursion and Java cases need parity tests. | F3 |
| `extract-function` | Extract Function | `Ctrl+Alt+M` | Partial | Real IDEA generator supports captured parameters, return values, multi-statements and scopes; control-flow, smart-cast, receiver and duplicate matrices remain. | F6 |
| `introduce-variable` | Introduce Variable | `Ctrl+Alt+V` | Partial | Common expression extraction, duplicate replacement and type/`val`/`var` choices work; advanced contexts remain. | F6 |
| `introduce-constant` | Introduce Constant | `Ctrl+Alt+C` | Partial | Compile-time constants and top-level/companion targets work; broader IDEA contexts remain. | F6 |
| `introduce-property` | Introduce Property | `Ctrl+Alt+F` | Partial | Class/constructor/companion targets work; delegated and complex receiver cases remain. | F6 |
| `introduce-type-alias` | Introduce Type Alias | `Ctrl+Alt+Shift+A` | Partial | Simple alias extraction works; generic type parameter extraction is intentionally not implemented. | F3 |
| `introduce-import-alias` | Introduce Import Alias | Refactor menu | Partial | Import/reference invocation and in-file replacements work; broader import edge cases remain. | F6 |
| `introduce-parameter` | Introduce Parameter | `Ctrl+Alt+P` | Partial | Change Signature pipeline updates callers; context parameters and adding an absent primary constructor remain. | F6 |
| `introduce-functional-parameter` | Introduce Functional Parameter | `Ctrl+Alt+Shift+P` | Partial | K2 extraction/Change Signature path covers single expressions; multi-statement and idiomatic lambda cases remain. | F6 |
| `move-declaration` | Move top-level declaration | Refactor → Move Declaration | Partial | Real K2 move/retargeting engine is used; only top-level declarations are exposed and Undo Last Refactoring is not yet transactional. | F1 |
| `copy-declaration` | Copy declaration | Refactor → Copy / F5 | Partial | Top-level copy and internal retargeting work; target selection and transaction semantics require consolidation. | F1 |
| `extract-interface-superclass` | Extract Interface / Superclass | Refactor menu | Partial | K2 Extract Super engine is wired; advanced constructors, generics and full conflicts remain. | F6 |
| `pull-members-up` | Pull Members Up | `Ctrl+Alt+U` | Partial | Direct target-member conflicts work; project-index-based hierarchy, visibility and accidental-override checks remain. | F2 |
| `push-members-down` | Push Members Down | `Ctrl+Alt+O` | Partial | Direct subclass mutation works; conflict discovery must be backed by project hierarchy search. | F2 |

## Absent IDEA families

| ID | IDEA family | Why absent | Target |
|---|---|---|---|
| `inline-type-alias` | Inline Type Alias | The K2 processor is not compiled or adapted to NetBeans. | F5 |
| `inline-anonymous-function` | Inline Anonymous Function/Lambda | The K2 processor is not compiled or adapted to NetBeans. | F5 |
| `move-file` | Move Kotlin file/directory | Requires filesystem transaction, source-root and project search services. | F4 |
| `change-package` | Change Package | Requires package/filesystem move support and usage retargeting. | F4 |
| `move-nested-member-method` | Move nested class/member/method | Requires receiver/visibility/hierarchy conflict support. | F4 |
| `rename-file-package-directory` | Rename file/package/directory | Requires NetBeans filesystem refactoring integration and Kotlin-aware update rules. | F7 |

## Cross-cutting milestones

| Milestone | Outcome |
|---|---|
| **F0** | This coverage matrix, status definitions, and a machine-checked baseline. |
| **F1** | Reusable transactional multi-file document/file mutation with rollback and uniform Undo/Redo. |
| **F2** | Project file, reference, symbol and hierarchy search bridge for standalone K2. |
| **F3** | Close high-value gaps in existing commands: Move, Pull/Push, Inline and generic Type Alias. |
| **F4** | File, directory, package, nested declaration, member and method move family. |
| **F5** | Inline Type Alias and Inline Anonymous Function. |
| **F6** | Advanced Extract/Introduce and Extract Super parity. |
| **F7** | Full Rename/Safe Delete filesystem, automatic-renamer and Java-bridge coverage. |
| **F8** | Structural conversions such as lambda/reference and operator/function conversions. |

## Representative compatibility baseline

The existing NetBeans tests are the initial executable sample set. They intentionally cover portable K2 scenarios, not the entire IntelliJ test corpus:

| Scenario | NetBeans test | Classification |
|---|---|---|
| Extract Function with captured locals in top-level scope | `KaExtractFunctionTest` | Exact parity baseline |
| Change Signature across declaration and separate call site | `KaChangeSignatureTest` | Exact parity baseline |
| Inline Function from a call site with multiple usages | `KaInlineFunctionTest` | Exact parity baseline |
| Move Declaration with external usage retargeting | `KaMoveDeclarationTest` | Exact parity baseline; undo is a known F1 gap |
| Pull Members Up direct target collision | `KaPullMembersUpComputerTest` | Documented standalone limitation beyond direct collision |
| Push Members Down into direct subclasses | `KaPushMembersDownComputerTest` | Documented standalone limitation: conflict scan is incomplete |
| Introduce Type Alias on generic type text | `KaIntroduceTypeAliasTest` | Documented deviation: no generic type-parameter extraction |

When a future port changes a row from Partial to Complete, it must add or strengthen the relevant compatibility fixture before changing this matrix status.
