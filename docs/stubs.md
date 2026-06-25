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

### Refactoring engine stubs (added for E9.3 Inline Variable)

Required to run IDEA's K2 `codeInliner/` engine in standalone mode.  The engine's class
hierarchy references `BaseRefactoringProcessor` (an abstract IDEA class that drives the full
modal refactoring pipeline with a `UsageView` dialog), but in our NB adapter we never call
`processor.run()` — we only call `createReplacementStrategyForProperty()`.  These stubs satisfy
JVM linkage without pulling in the rest of the IDEA refactoring runtime.

| Class | Location | Kind | Purpose |
|-------|----------|------|---------|
| `BaseRefactoringProcessor` | `Nbm/src/main/java/com/intellij/refactoring/` | Runtime | Abstract superclass of `AbstractKotlinDeclarationInlineProcessor`; three constructors `(Project)`, `(Project, Runnable)`, `(Project, SearchScope, Runnable)`; abstract methods declared; `run()` throws `UnsupportedOperationException` (never called) |
| `UsageViewDescriptor` | `Nbm/src/main/java/com/intellij/usageView/` | Compile-only | Interface required by `BaseRefactoringProcessor.createUsageViewDescriptor()` abstract method signature |

### Service stubs registered at session startup

These are not class-file overrides but *service registrations* performed in
`KotlinAnalysisAPISession.registerStandaloneServices()` and
`KotlinAnalysisAPISession.installNoOpPsiSearchHelper()`.

| Service interface | Implementation | Scope | Purpose |
|------------------|----------------|-------|---------|
| `PsiSearchHelper` (`com.intellij.psi.search`) | `NoOpPsiSearchHelper` (`io.github.nbplugins.kotlin.nbm.resolve`) | Project | Called by `SearchRequestQuery.processResults` regardless of the search EP.  No-op: `getUseScope` returns `LocalSearchScope(file)`, booleans return `true`, arrays return empty. |
| `ShortenReferencesFacility` (`org.jetbrains.kotlin.idea.base.codeInsight`) | `KotlinSymbolBasedShortenReferencesFacility` (`io.github.nbplugins.kotlin.refactoring`) | Application | Called by `InlinePostProcessor.shortenReferences` after each inline substitution.  Wraps `SymbolBasedShortenReferencesFacility` (IDEA `internal` class) via Kotlin delegation — wrapper lives in `KotlinRefactoring` module which can access `internal` Kotlin symbols of that module. |

## Active resource replacements

These resource files in `Nbm/src/main/resources/` win over bundled JARs via classloader order:

| File | Why |
|------|-----|
| `messages/JavaCoreBundle.properties` | Absent from `core:253`; required by `LanguageLevel.<clinit>` at runtime |
| `messages/JavaErrorMessages.properties` | Absent from `core:253`; required by `LanguageLevel.<clinit>` at runtime |

## Conflict resolution rule

When two versions of the same class come from different JARs:
**always strip the old version, keep the new one.**
If new code calls a method absent from the old class, add a stub method in the old class
(placed in `Nbm/src/main/java/`; the main JAR loads first and overrides `ext/*.jar`).
