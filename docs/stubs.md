# IntelliJ Platform Stubs

This document catalogues every stub and compatibility shim the plugin adds to bridge the gap
between the bundled IntelliJ platform JARs and the standalone NetBeans runtime.

## Why stubs are needed

The plugin uses IDEA's K2 analysis engine and refactoring infrastructure in *standalone* mode —
outside the IDEA application container.  Many IDEA classes reference IDE services
(`ApplicationManager`, `PsiSearchHelper`, `ShortenReferencesFacility`, …) that are normally
provided by the IDEA platform at startup.  In our NetBeans embedding those services are either
absent or behave differently, so we provide:

- **Compile-only stubs** — satisfy the compiler / linker but are never invoked at runtime.
- **Runtime no-op stubs** — called at runtime; return safe defaults instead of crashing.
- **Runtime service registrations** — register a NB-aware or no-op implementation as an IDEA
  application/project service so that IDEA code that calls `service<Foo>()` / `Foo.getInstance()`
  finds a live object.

## Stubs in `Nbm/src/main/java/` and `Nbm/src/main/kotlin/`

These classes take classloader precedence over any bundled JAR because `Nbm` is loaded first.

### Formatter infrastructure stubs (added for A4.9 / D-series)

Required when `code-style:253` / `code-style-impl:253` replaced the old
`openapi-formatter.jar` / `idea-formatter.jar`.

| Class | Package | Kind | Purpose |
|-------|---------|------|---------|
| `Configurable` | `com.intellij.openapi.options` | Compile-only | Return type of `createSettingsPage()` (throws) |
| `IndentOptionsEditor` | `com.intellij.application.options` | Compile-only | Return type of `getIndentOptionsEditor()` (returns null) |
| `CodeStyleSettingsProvider` | `com.intellij.psi.codeStyle` | Runtime | `EXTENSION_POINT_NAME` field needed for extension registration |
| `LanguageCodeStyleSettingsProvider` | `com.intellij.psi.codeStyle` | Runtime | `EP_NAME` field needed for extension registration |
| `CodeStyleSettingsCustomizable` | `com.intellij.psi.codeStyle` | Compile-only | Interface; implemented by IDEA code-style settings classes |
| `CodeStyleSettingsService` | `com.intellij.psi.codeStyle` | Runtime | `getInstance()` returns no-op (empty factory lists) |
| `CustomCodeStyleSettingsManager` | `com.intellij.psi.codeStyle` | Runtime | `getCustomSettings()` uses reflection to create settings in standalone mode |
| `Formatter` | `com.intellij.formatting` | Runtime | `getInstance()` returns `new FormatterImpl()` singleton (253-era `getInstance()` returns null in standalone mode) |
| `ConcurrentCollectionFactory` | `com.intellij.concurrency` | Runtime | `concurrency:253` module not published — provides `createConcurrentIdentityMap()` etc. needed by `code-style-impl` |
| `ConcurrencyUtil` | `com.intellij.util` | Runtime | `computeIfAbsent(UserDataHolder, Key, Supplier)` absent from `util:253`; called by `analysis-api:2.3.21` |
| `Registry` | `com.intellij.openapi.util.registry` | Runtime | Stripped from `CoreImpl`; exposes `Companion` inner class so `analysis-api:2.3.21` Kotlin code can access `Registry.Companion` |
| `RegistryValue` | `com.intellij.openapi.util.registry` | Runtime | Used by the `Registry` stub above |
| `Editor` | `com.intellij.openapi.editor` | Compile-only | Referenced by `NetBeansFormattingModel` parameter type |
| `CodeInsightContextManager` | `com.intellij.codeInsight.multiverse` | Runtime (Kotlin) | Overrides `core:253` interface; adds `isSharedSourceSupportEnabled(): Boolean = false` — absent from 253 interface but called via `invokeinterface` by `kotlin-compiler-ir-for-ide:2.3.21` |
| `KotlinSettingsProvider` | `com.intellij.formatting` | Runtime | Plugin-specific; extends `CodeStyleSettingsProvider`; provides `KotlinCodeStyleSettings` factory |
| `KotlinLanguageCodeStyleSettingsProvider` | `com.intellij.formatting` | Runtime | Plugin-specific; extends `LanguageCodeStyleSettingsProvider`; provides Kotlin code-style settings UI |

### Platform highlight-type stub (era-253 submodule bump)

Required because the real `HighlightInfoType` static initializer cannot run in standalone mode.

| Class | Package | Kind | Purpose |
|-------|---------|------|---------|
| `HighlightInfoType` | `com.intellij.codeInsight.daemon.impl` | Runtime | Replaces the platform interface whose `<clinit>` crashes with `AssertionError: Must be precomputed` in standalone mode — in era-242 via `assertBundlePrecomputed()`, in era-253 via `CodeInsightColors.*_ATTRIBUTES` / `HighlightDisplayKey.findOrRegister()` reaching `JBUIScale.computeSystemScaleFactor()`. The stub exposes only `SYMBOL_TYPE_SEVERITY` and `HighlightInfoTypeImpl` (with both 2-arg and 3-arg constructors for binary compatibility with era-242 and era-253 submodule code respectively). |

### Refactoring engine stubs (added for E9.3 Inline Variable)

Required to run IDEA's K2 `codeInliner/` engine in standalone mode.  The engine's class
hierarchy references `BaseRefactoringProcessor` (an abstract IDEA class that drives the full
modal refactoring pipeline with a `UsageView` dialog), but in our NB adapter we never call
`processor.run()` — we only call `createReplacementStrategyForProperty()`.  These stubs satisfy
JVM linkage without pulling in the rest of the IDEA refactoring runtime.

| Class | Location | Kind | Purpose |
|-------|----------|------|---------|
| `BaseRefactoringProcessor` | `Nbm/src/main/java/com/intellij/refactoring/` | Runtime | ABI-compatible superclass of the inline and Push Members Down processors. Its non-modal `run()` executes the standard `findUsages → preprocessUsages → performRefactoring` lifecycle without IDEA's Usage View; NetBeans owns the dialog, preview, and undo transaction. |
| `UsageViewDescriptor` | `Nbm/src/main/java/com/intellij/usageView/` | Compile-only | Interface required by `BaseRefactoringProcessor.createUsageViewDescriptor()` abstract method signature |
| `MoveRenameUsageInfo` | `Nbm/src/main/java/com/intellij/refactoring/util/` | Runtime (shadows `analysis:253`) | Superclass of the Copy Declaration engine's `K2MoveRenameUsageInfo` (E9.19). The real platform ctor calls `PsiDocumentManager.getDocument` and asserts `refEnd <= document.getTextLength()`; the standalone MockProject keeps no live document for mutated PSI, so this stub provides the same ABI (3-arg + 6-arg ctors, `getReferencedElement()`) with **no document access**. `KotlinRefactoring` still compiles against the real `analysis:253` class; this shadows it at runtime because `Nbm` classes load first. |

**Inline Anonymous Function/Lambda (F5.2)** requires no new class or service stub. IDEA's
`KotlinInlineAnonymousFunctionProcessor` converts a lambda into fresh anonymous-function PSI and
then immediately analyses it. A standalone K2 session cannot map that newly inserted PSI to FIR in
the same session (`FirDeclaration ... fir is null`), so the NetBeans adapter uses the existing IDEA
`LambdaToAnonymousFunctionUtil` for conversion, refreshes the standalone session, then invokes the
existing IDEA `CodeInliner`. Only final text is committed through `KotlinRefactoringTransaction`.

### Copy Declaration engine port (added for E9.19)

The Copy Declaration multi-declaration path reuses IDEA's real retargeting engine
`K2MoveRenameUsageInfo` (from `kotlin.refactorings.move.k2`), compiled into `KotlinRefactoring`
via `maven-resources-plugin` (same pattern as the E9.3 inline sources).  Its base class
`com.intellij.refactoring.util.MoveRenameUsageInfo` lives in the platform `analysis:253` jar (same
era as core's `UsageInfo`, so no era mismatch); it is a `provided` compile dep of `KotlinRefactoring`
and supplied at runtime by `Nbm`'s existing `analysis` dependency — **no stub needed**.

Groovy patches applied in `KotlinRefactoring/pom.xml` (patch #14) to run it standalone:

| Patch | Why |
|-------|-----|
| Drop the `Light` nested class + `find`/`findExternalUsages`/`preProcessUsages` (and their imports for `MoveClassHandler`/`MoveMemberHandler`/`MoveMembersProcessor`, `asJava.toLightElements`, `projectScope`, `ReferencesSearch`) | Java-reference + external-usage paths are unused for Kotlin declaration copy and reference platform move handlers not on the standalone classpath |
| `allowAnalysisFromWriteActionInEdt(x) { }` → `analyze(x) { }` | The wrapper was removed in analysis-api 2.3.21 (same as patch #1 for `shortenUtils.kt`) |
| `ProgressManager.getInstance().progressIndicator` → `… ?: EmptyProgressIndicator()` | The standalone container has no progress indicator |
| `markInternalUsageInfo`: `expr.mainReference` → `expr.references.filterIsInstance<KtReference>().firstOrNull() ?: return` | The local `mainReference` stub throws on a `KtCallExpression` (no invoke-reference contributor standalone); skipping it matches IDEA's net effect since the callee simple-name reference is processed separately |

The two trivial `groupByFile`/`sortedByOffset` helpers from `moveUsageUtil.kt` are copied verbatim
into `KotlinRefactoring/src/main/kotlin/.../move/processor/MoveUsageUtil.kt` (the full file drags in
the unrelated move-descriptor machinery).

### Module stubs (added for E9.7 Move Declaration)

`KotlinRefactoring` provides its own `SingletonModule`/`ModuleUtilCore`/`ModuleType` (package
`com.intellij.openapi.module`) so IDEA's ported move-conflict checks can resolve a real, correctly
behaving `Module` in this plugin's single-module architecture (see `SingletonModule`'s doc
comment). In the packaged `.nbm`, a bundled platform JAR provides its own real `ModuleUtilCore`
whose `findModuleForPsiElement()` calls `ProjectFileIndex.getInstance()` — a service never
registered in this standalone environment — throwing `IllegalStateException`. Classloading order in
the full `.nbm` let that real class win over `KotlinRefactoring`'s copy (unlike in unit tests, where
the classpath differs and the plugin's own copy was found first).

| Class | Location | Kind | Purpose |
|-------|----------|------|---------|
| `SingletonModule` | `Nbm/src/main/kotlin/com/intellij/openapi/module/` | Runtime (shadows a bundled platform JAR) | Duplicate of `KotlinRefactoring`'s class, same FQN, so `Nbm`'s classloader-first-loaded copy wins at runtime |
| `ModuleUtilCore` | `Nbm/src/main/kotlin/com/intellij/openapi/module/` | Runtime (shadows a bundled platform JAR) | Same duplication; `findModuleForPsiElement()` never touches `ProjectFileIndex` |
| `ModuleType` | `Nbm/src/main/java/com/intellij/openapi/module/` | Runtime (shadows a bundled platform JAR) | Same duplication; `isInternal()` always `false` |

### Change Signature engine port (added for E9.8)

`KotlinChangeSignatureUsageSearchService` (interface in `KotlinRefactoring`, real implementation
`KotlinChangeSignatureUsageSearchServiceImpl` in `Nbm`) backs every `ReferencesSearch`-style call
the ported engine makes — same pattern as `KotlinMoveUsageSearchService` for E9.7. A single
whole-project scan finds plain calls, parameter references, callable references, data-class
destructuring, and by-convention operator calls; `findOverridings` and
`findConstructorDelegationCallers` are two further, separate scans for usage kinds the general scan
structurally can't reach (see the implementation's class doc for the full breakdown of which PSI
node types need which scan).

Two narrow standalone-environment gaps found while porting, not upstream bugs:

- `shortenReferences()` throws for *any* constructor (not just primary) after a *structural*
  parameter-list change — `KotlinIllegalArgumentExceptionWithAttachments: Error while resolving
  Fir(Primary)ConstructorImpl from ANNOTATION_ARGUMENTS to BODY_RESOLVE`. Root cause: a structural
  change swaps in a brand-new parameter-list PSI node via a raw, non-transactional mutation — this
  plugin's `NoOpPomModel.runTransaction` stub never fires the real "out-of-block modification"
  notification a live IDE would, so the low-level FIR cache never invalidates before
  `shortenReferences()` forces the constructor's FIR node to `BODY_RESOLVE` phase. Functions
  tolerate the stale cache state; constructors do not. Not practical to fully fix standalone (would
  mean implementing real `PomModel` transaction semantics); both call sites in
  `KotlinChangeSignatureUsageProcessor.updatePrimaryMethod()` now swallow the failure and fall back
  to un-shortened, fully-qualified type names for the constructor's own parameter list.
- A data-class destructuring entry's reference (`KaFirDestructuringDeclarationReference`, a
  `KtMultiReference`) doesn't resolve through `resolveToSymbol()` at all — always returns `null`.
  It only resolves through `multiResolve()`, which returns *two* results: the entry's own
  declaration and the constructor parameter it destructures. `KotlinChangeSignatureUsageSearchServiceImpl`
  falls back to `multiResolve()` when `resolveToSymbol()` comes back empty.

### Service stubs registered at session startup

These are not class-file overrides but *service registrations* performed in
`KotlinAnalysisAPISession.registerStandaloneServices()` and
`KotlinAnalysisAPISession.installNoOpPsiSearchHelper()`.

| Service interface | Implementation | Scope | Purpose |
|------------------|----------------|-------|---------|
| `PsiSearchHelper` (`com.intellij.psi.search`) | `NoOpPsiSearchHelper` (`io.github.nbplugins.kotlin.nbm.resolve`) | Project | Called by `SearchRequestQuery.processResults` regardless of the search EP.  No-op: `getUseScope` returns `LocalSearchScope(file)`, booleans return `true`, arrays return empty. |
| `NullableNotNullManager` (`com.intellij.codeInsight`) | `NullableNotNullManagerImpl` (`com.intellij.codeInsight`) | Project | The abstract base ships in the repacked `kotlin-compiler` JAR, but no implementation is published in any bundled artifact, so the static `getNullability`/`isNullable`/`isNotNull` helpers failed and J2K lost annotation-based nullability inference. The implementation honours direct nullability annotations plus JSR-305 `@TypeQualifierDefault`; nickname annotations and Checker Framework defaults are not scanned. It must sit in `com.intellij.codeInsight` because `getNullityDefault` is package-private. |
| `ShortenReferencesFacility` (`org.jetbrains.kotlin.idea.base.codeInsight`) | `KotlinSymbolBasedShortenReferencesFacility` (`io.github.nbplugins.kotlin.refactoring`) | Application | Called by `InlinePostProcessor.shortenReferences` after each inline substitution.  Wraps `SymbolBasedShortenReferencesFacility` (IDEA `internal` class) via Kotlin delegation — wrapper lives in `KotlinRefactoring` module which can access `internal` Kotlin symbols of that module. |
| `KotlinMemberInfoSupport` / `KotlinMemberInfoStorageSupport` (`org.jetbrains.kotlin.idea.refactoring.memberInfo`) | `K2MemberInfoSupport` / `K2MemberInfoStorageSupport` | Application | Supplies K2 member labels, override metadata, and hierarchy membership needed by Extract Super (E9.15/E9.16) and Pull Members Up (E9.17). |
| `StandaloneInheritorSearch`, `forEachOverridingElement`, and `KotlinSearchUsagesSupport` hierarchy methods | `KotlinStandaloneInheritorSearch` (`io.github.nbplugins.kotlin.nbm.refactoring`) | Active standalone session | Replaces IDEA indexed Kotlin inheritor, overrider, and direct-super lookups with K2 scans of source files in the active build session. Java/library results and complete index-backed conflict checks remain unsupported. |
| Kotlin `PullUpHelper` language extension (`com.intellij.refactoring.memberPullUp`) | `K2PullUpHelperFactory` | Application extension | Resolves the copied lifecycle-free `PullUpProcessor` to IDEA's real K2 Kotlin member-move helper. Used by Extract Super and Pull Members Up. |

### Push Members Down engine port (added for E9.18)

`KotlinRefactoring` compiles the original IDEA K2 Push Down source set through
`maven-resources-plugin`; `KaPushMembersDownComputer` supplies selected `KotlinMemberInfo`
objects and invokes its normal processor lifecycle. The following standalone seams let the copied
engine operate against K2 source PSI rather than IDEA indexes:

| Component | Location | Purpose |
|-----------|----------|---------|
| `K2PushDownProcessorRunner` | `KotlinRefactoring` | Public bridge in the processor package that creates and runs the copied K2 processor without changing its transfer, removal, substitution, marking, or conflict algorithms. |
| `HierarchySearchRequestStub` / `StandaloneInheritorSearch` | `KotlinRefactoring` | Replaces index-backed direct-inheritor queries with a scan of writable Kotlin PSI registered in the standalone K2 session. |
| `KotlinPushDownTargetSearchService` | session service | Supplies source-scope direct-subclass targets to the engine; it bypasses unavailable IDEA indexes. |
| `Messages`, `RefactoringBundle`, `ProgressUtils`, and Push Down compatibility files | `Nbm` / `KotlinRefactoring` | Provide non-modal UI/progress and API-era ABI contracts. Conflict presentation remains NetBeans's responsibility. |

The processor invalidates standalone FIR caches before it marks freshly inserted members, since
raw PSI mutations do not trigger IDEA's normal PSI/POM invalidation events. The NetBeans apply
element snapshots every session-owned document before mutation, formats changed Kotlin files with
the active project style, and restores each snapshot for **Undo Last Refactoring**.

### Pull Members Up limitations

E9.17 reuses the K2 mutation engine and supports source and target classes in different Kotlin files.
The standalone conflict preview currently detects direct target-member name collisions. IDEA's additional
project-index-backed checks (inheritor accidental overrides and full cross-project visibility search) rely on
IDE services not present in the standalone Analysis API environment and are deliberately not simulated.

## J2K engine port (added for E10)

Java → Kotlin conversion runs IDEA's real K2 J2K engine, compiled from
`submodules/IntellijCommunity` into the `KotlinJ2K` reactor module
(`io.github.nbplugins:netbeans-plugin-kotlin-j2k`). `KotlinJ2K/pom.xml` copies the sources at
`initialize`, applies patch scripts at `generate-sources`, and compiles them with `-proc:none` like
`Nbm`.

**Copied**: `j2k/shared/src`, `j2k/k2/src`, and the support sources `DocCommentConverter.kt`,
`DocumentUtils.kt`, `KotlinPsiHeuristics.kt`, `KotlinUserDataUtils.kt`, `visibilityUtil.kt`,
`KotlinStubUtils.kt`. **Not copied**: `j2k/k1.new*`, `j2k/k1.old*`, and the copy/paste entry points
(`j2k/*/copyPaste/**`, `KotlinPasteFromJavaDialog.java`, `J2KKotlinConfigurationHelper.kt`) — they
need the IDE project model or editor integration that the conversion path never touches.

**Patches**: the K2 post-processor and its processing groups are widened from `internal` to
`public`; `J2kConverterExtension` and `K2J2KConverterExtension` lose the copy/paste members and the
configuration-helper calls.

### Stubs in `KotlinJ2K/src/main/kotlin/`

| Stub | Kind | Behaviour |
|------|------|-----------|
| `KotlinPluginModeProvider` | compile + runtime | reports K2 mode |
| `Kotlin*Index` objects | runtime no-op | return empty lists (no stub index in a standalone session) |
| `codeInspection/dataFlow/*` (`DfaUtil`, `DfaPsiUtil`, `NullabilityUtil`, `DfType`…) | runtime degraded | annotation-based nullability only |
| `JavaSourceInference.inferNullability` | runtime degraded | reads nullability annotations from the Java PSI |
| `localCanBeFinal/LocalCanBeFinal.getControlFlow` | **real** | delegates to the era-193 control-flow engine bundled in `KotlinCompilerCliBase` |
| `siyeh/ig/psiutils/*` | mixed | `FinalUtils` is real (control flow); `ExpectedTypeUtils`, `ExpressionUtils`, `MethodCallUtils`, `VariableAccessUtils` return "unknown" |
| `idea/j2k/IdeaDocCommentConverter` | runtime degraded | simplified Javadoc → KDoc (no `@param`/`@return` reformatting) |

### Runtime registrations in `KotlinAnalysisAPISession`

| Registration | Why |
|--------------|-----|
| `StandaloneKaModuleProvider` (project service, replaces `KaModuleProvider`) | IDEA's provider resolves through the module index; the standalone session answers from `modulesWithFiles` |
| `org.jetbrains.kotlin.j2kConverterExtension` + `K2J2KConverterExtension` | without the EP, `J2kConverterExtension.extension(K2)` throws `NoSuchElementException` |
| `com.intellij.deepestSuperMethodsSearch` + `PsiDeepestSuperMethodsQueryExecutor` | `java-impl` declares this EP in `plugin.xml`; without it `PsiMethod.findDeepestSuperMethods()` (used by `deepestFqName` for `equals`/`hashCode`/`toString`) throws `Missing extension point`. The executor walks `PsiSuperMethodImplUtil.findSuperMethods` — PSI only, no index |

### Bridges in `KotlinRefactoring`

`KotlinOptimizeImportsFacility` keeps IDEA's interface shape; `analyzeImports`/
`prepareOptimizedImports` return `null` (no resolution index for unused-import detection), while
`replaceImports` is the real implementation. `SearchUtils.findDeepestSuperMethodsNoWrapping` and
`Module.toKaSourceModuleWithElementSourceModuleKindOrProduction` resolve through K2/the session
provider.

### Limitations

- **No Java copy/paste conversion** (the copy/paste post-processors are not ported).
- **No project-configuration prompts** (IDEA's "convert the whole module?" flow).
- **Unused-import detection is off** during conversion, so imports are kept as the engine emitted
  them.
- **Formatting is text-based**: the engine's `FormatCodeProcessing` calls `CodeStyleManager.reformat`,
  which is a no-op in the standalone mock application, so the adapter runs the converted text through
  `KotlinFormatterUtils` with the project's Kotlin settings.
- **No editor-level undo**: the conversion runs inside a NetBeans refactoring session (opened from
  the Refactoring window), so Ctrl+Z in the new Kotlin buffer does not revert it. **Undo Last Refactoring** does: it restores the `.java`
  with its original text and removes the `.kt` in one step. The stale sibling `.class` file is
  deleted alongside the `.java` and is build output, so undo does not restore it.

## Active resource replacements

These resource files in `Nbm/src/main/resources/` win over bundled JARs via classloader order:

| File | Why |
|------|-----|
| `messages/JavaCoreBundle.properties` | Absent from `core:253`; required by `LanguageLevel.<clinit>` at runtime |
| `messages/JavaErrorMessages.properties` | Absent from `core:253`; required by `LanguageLevel.<clinit>` at runtime |

## Never shadow a bundled class with a partial stub

`KotlinRefactoring` used to declare `com.intellij.psi.impl.source.resolve.JavaResolveUtil`
(added for E9.7 Move Declaration) with only `isAccessible(...)`. The module classloader let that
partial copy win over the full era-193 implementation already shipped by
`netbeans-plugin-kotlin-compiler-cli-base`, so every `substituteResults(PsiJavaCodeReferenceElement,
JavaResolveResult[])` call inside the bundled K2 engine threw `NoSuchMethodError`. In the IDE this
surfaced as an empty Navigator panel, missing error highlighting, and a broken unused-import check
for any Kotlin file whose supertypes reach Java library classes.

The stub is gone: `KotlinRefactoring` now declares
`netbeans-plugin-kotlin-compiler-cli-base` as a `provided` dependency (compile-time only; the JAR is
bundled once, by `Nbm`). `BundledClassShadowingTest` fails the build if a `JavaResolveUtil.class`
entry reappears in the refactoring module and verifies that the runtime class exposes
`substituteResults`.

Note the precedence trap: in unit tests the plugin's class directory comes first, so a shadow that
is broken in the packaged `.nbm` can look healthy in tests. A partial stub is not a valid shim
here — the bundled engine links against the whole original API.

## Conflict resolution rule

When two versions of the same class come from different JARs:
**always strip the old version, keep the new one.**
If new code calls a method absent from the old class, add a stub method in the old class
(placed in `Nbm/src/main/java/`; the main JAR loads first and overrides `ext/*.jar`) — but only
when the replacement is complete: a partial shadow of a bundled class breaks the bundled callers
(see above).
