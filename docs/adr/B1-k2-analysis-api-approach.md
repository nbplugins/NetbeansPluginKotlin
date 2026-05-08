# ADR B1 — K2 Analysis API: Architectural Approach

**Status:** Accepted

---

## Context

The plugin currently uses Kotlin 1.3.72 / FE1.0 for all semantic analysis:
- `KotlinEnvironment` (singleton per NetBeans project) initializes a `JavaCoreProjectEnvironment` / `MockProject`
- `TopDownAnalyzerFacadeForJVM` runs full type inference and produces a `BindingContext`
- `BindingContext` is consumed in 29 files across resolve, completion, diagnostics, hints, highlighting, and navigation

K2 (FIR — Frontend Intermediate Representation) was shipped in Kotlin 2.0 (May 2024) and replaces FE1.0. K2 exposes analysis through the **Kotlin Analysis API** (`KaSession`, `KaSymbol`, `KaType`).

Migration to K2 is needed to support modern language features (value classes, context receivers, etc.), correct JPMS classpath, and navigation into JDK/stdlib.

The `submodules/IntellijCommunity` submodule currently points to IDEA 2019.3.5 (pre-K2). B2 will update the submodule to a modern version.

---

## Decision

Use **`org.jetbrains.kotlin:analysis-api-standalone-for-ide`** as the K2 analysis backend.

This artifact provides a standalone session builder that runs K2 analysis without requiring a full IntelliJ IDEA application. Internally it still creates a lightweight `Application`/`Project` object, but their lifecycle is managed entirely by the session builder — the NetBeans plugin does not need to initialize or interact with these objects directly.

### Entry point

```kotlin
val session = buildStandaloneAnalysisAPISession {
    buildKtModuleProvider {
        // register source module with KtSourceModule
        // register library modules (JDK, dependencies)
    }
}

// All analysis within analyze { } blocks:
analyze(ktElement) {
    val type: KaType? = expression.expressionType
    val symbol: KaSymbol? = ktDeclaration.symbol
    val diagnostics: Collection<KaDiagnostic> = ktFile.diagnostics(filter)
}

session.dispose()
```

### K1 → K2 concept mapping

| K1 (current) | K2 (target) |
|---|---|
| `KotlinEnvironment` (singleton) | `StandaloneAnalysisAPISession` (per project) |
| `TopDownAnalyzerFacadeForJVM` | `buildStandaloneAnalysisAPISession` |
| `BindingContext` | `KaSession` (within `analyze {}`) |
| `DeclarationDescriptor` | `KaSymbol` |
| `KotlinType` | `KaType` |
| `BindingContext.diagnostics` | `ktFile.diagnostics(filter)` |
| `ReferenceVariantsHelper` | `KaSession` scope member enumeration |
| `getResolutionScope(element, bindingContext)` | `analyze(element) { … }` block |
| `ComponentProvider.getService()` | `KaSession` extension functions |

### Artifact coordinates

```xml
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>analysis-api-standalone-for-ide</artifactId>
    <version>${kotlin2.version}</version>
</dependency>
```

Repository (in addition to Maven Central):
```
https://packages.jetbrains.team/maven/p/ij/intellij-dependencies
```

---

## Consequences for B2

1. **Replace `KotlinEnvironment`** with `StandaloneAnalysisAPISession` — create/dispose per NetBeans project; wire into NetBeans project lifecycle.
2. **Migrate all 29 `BindingContext` call sites** to `analyze {}` blocks.
3. **Replace `KotlinCacheServiceImpl` / `NetBeansAnalyzerFacadeForJVM`** with session-based analysis.
4. **Replace `ReferenceVariantsHelper`** (from bundled `kotlin-ide-common`) with `KaSession` scope queries.
5. **Bump kotlin-compiler** to a K2-capable version (≥ 2.0.0); update language version settings.
6. **New code** goes into package `io.github.nbkotlinplugin.*` (as per development plan B2).

---

## Rejected alternatives

### A. Initialize a full IntelliJ IDEA application

Would require the entire IntelliJ platform runtime — hundreds of MBs, GUI lifecycle, service container bootstrap. Incompatible with NetBeans deployment model.

### B. Compile and bundle source code from `submodules/IntellijCommunity/plugins/kotlin`

`plugins/kotlin/` is the built-in IDEA Kotlin plugin — tightly coupled to IntelliJ platform internals (`Application`, `Project`, extension points, PSI infrastructure). It is not designed for use outside the platform. Even after B2 updates the submodule to a modern version, bundling `plugins/kotlin/` sources would mean depending on unstable internal APIs that change with each IDEA release. The standalone artifact is the officially documented, versioned path for third-party plugin developers.

**Note:** `submodules/IntellijCommunity/plugins/kotlin` (after the B2 submodule update) remains a valuable **reference**: it shows how the Kotlin IDEA plugin implements completion, diagnostics, navigation, and highlighting on top of `KaSession`. New NetBeans adapter code in B2 should be written by studying these patterns and adapting them to the NetBeans CSL API — not by copying and bundling the source directly.

---

## References

- [Kotlin Analysis API documentation](https://kotlin.github.io/analysis-api/)
- [Standalone mode fundamentals](https://kotlin.github.io/analysis-api/fundamentals.html)
- [Maven artifact: analysis-api-standalone-for-ide](https://mvnrepository.com/artifact/org.jetbrains.kotlin/analysis-api-standalone-for-ide)
- [`StandaloneAnalysisAPISessionBuilder` source](https://github.com/JetBrains/kotlin/blob/master/analysis/analysis-api-standalone/src/org/jetbrains/kotlin/analysis/api/standalone/StandaloneAnalysisAPISessionBuilder.kt)
- Real-world usage: [kotlin-lsp](https://github.com/amgdev9/kotlin-lsp), [detekt #8021](https://github.com/detekt/detekt/issues/8021)
