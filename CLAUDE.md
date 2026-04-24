# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Kotlin plugin for NetBeans IDE** — a JetBrains-developed plugin providing Kotlin language support in NetBeans. **Note**: This project is no longer actively developed (see [issue #122](https://github.com/JetBrains/kotlin-netbeans/issues/122)).

## Build Commands

```bash
mvn clean install          # Build the plugin and install to local Maven repo
mvn clean package          # Build the plugin (produces .nbm file)
mvn test                   # Run all tests
mvn test -Dtest=ClassName  # Run a single test class
mvn clean package -DskipTests  # Build without running tests
mvn nbm:cluster-app        # Create a NetBeans test cluster for manual testing
```

## Architecture

The plugin integrates with NetBeans via the **CSL (Colored Syntax Language) API** using the MIME type `text/x-kt`. The entry point is `KotlinLanguage.java` which registers all language services.

### Mixed-Language Codebase
- **Java** (`~67 files`): NetBeans integration layer — service registrations, API adapters, and entry points
- **Kotlin** (`~164 files`): Core implementation logic — analysis, completion, refactoring, etc.

### Main Packages (`src/main/java/org/jetbrains/kotlin/`)

| Package | Purpose |
|---------|---------|
| `language/` | Language registration and configuration (`KotlinLanguage.java`) |
| `highlighter/` | Syntax and semantic token coloring |
| `completion/` | Code completion proposals |
| `diagnostics/` | Error/warning detection and reporting |
| `indexer/` | File indexing for symbol lookup |
| `navigation/` | Go-to-definition, find usages, class navigation |
| `refactorings/` | Rename, extract method, and other refactorings |
| `hints/` | Quick fixes and code intentions |
| `resolve/` | Kotlin AST resolution and symbol binding |
| `formatting/` | Code formatting using bundled IntelliJ formatter |
| `debugger/` | Debug session integration |
| `builder/` | Compilation support |
| `j2k/` | Java-to-Kotlin conversion |
| `project/` | Project type support and structure |
| `projectsextensions/` | Maven/Gradle/Ant build system integration |
| `utils/` | Shared helpers |

### Bundled JARs (`lib/`)
Several capabilities depend on bundled custom JARs (not from Maven Central):
- `kotlin-ide-common.jar`, `kotlin-formatter.jar`, `kotlin-converter.jar` — JetBrains IDE tooling
- `intellij-core.jar` — IntelliJ platform core used for Kotlin analysis
- `openapi-formatter.jar`, `idea-formatter.jar` — Formatting infrastructure

These JARs are installed into the local Maven repository (`repo/`) via `setup-local-repo.sh`.
Run it after modifying any JAR in `lib/`.

### JAR Patches (`patches-src/`)

The bundled JARs were compiled against older library versions and require bytecode patches to
work with Kotlin 1.3.72 and Java 17+. Patches are written using ASM and live in `patches-src/`.

| Patch | Input JAR | Problem fixed |
|-------|-----------|---------------|
| `InjectGetGreenStub.java` | `lib/intellij-core.jar` | Adds `getGreenStub()` to `SubstrateRef` and `StubBasedPsiElementBase` — method missing from the bundled IntelliJ version but called by kotlin-compiler 1.3.72 |
| `PatchKtNodeTypes.java` | `kotlin-compiler-1.3.72.jar` (Maven) | Fixes `KtNodeTypes.BODY` field declared as `KtNodeType` but accessed as `IElementType` — Java 17+ enforces strict descriptor matching |
| `PatchJvmPlatform.java` | `kotlin-compiler-1.3.72.jar` (Maven) | Converts `JvmPlatform` from interface to abstract class and adds `getDefaultImports()` — needed by `kotlin-converter.jar` compiled against Kotlin 1.1.1 |
| `PatchImportConversionKt.java` | `lib/kotlin-converter.jar` | Rewrites `ImportConversionKt` static initializer to use `JvmPlatformAnalyzerServices` instead of the removed `JvmPlatform.getDefaultImports()` |
| `PatchFqnPart.java` | `lib/kotlin-converter.jar` | Renames `ImportPath.fqnPart()` → `getFqName()` (renamed in 1.3.72); redirects `AddToStdlibKt.singletonList()` → `Collections.singletonList()` (removed in 1.3.72) |
| `PatchKotlinIdeCommon.java` | `lib/kotlin-ide-common.jar` | Removes `setShowInternalKeyword` call (property removed in 1.3.72); renames `KotlinTypeFactory.simpleType(5-arg)` → `simpleTypeWithNonTrivialMemberScope`; redirects `KotlinType.isError()` → static `KotlinTypeKt.isError(KotlinType)` |

**Applying patches** (run with Java 17):

```bash
cd /path/to/KotlinNetbeans
CP="patches-src:/usr/share/java/objectweb-asm/asm.jar"

# 1. Patch intellij-core.jar (input = .bak = original)
java -cp $CP InjectGetGreenStub lib/intellij-core.jar.bak lib/intellij-core.jar

# 2. Patch kotlin-converter.jar (chain: PatchImportConversionKt -> PatchFqnPart)
java -cp $CP PatchImportConversionKt lib/kotlin-converter.jar.bak /tmp/kc-step1.jar
java -cp $CP PatchFqnPart /tmp/kc-step1.jar lib/kotlin-converter.jar

# 3. Patch kotlin-ide-common.jar (input = original, from lib/)
java -cp $CP PatchKotlinIdeCommon lib/kotlin-ide-common.jar.bak lib/kotlin-ide-common.jar

# 4. Patch kotlin-compiler-1.3.72.jar from Maven (chain PatchKtNodeTypes -> PatchJvmPlatform)
KCJ=~/.m2/repository/org/jetbrains/kotlin/kotlin-compiler/1.3.72/kotlin-compiler-1.3.72.jar
java -cp $CP PatchKtNodeTypes $KCJ /tmp/kc-step1.jar
java -cp $CP PatchJvmPlatform  /tmp/kc-step1.jar /tmp/kc-patched.jar
cp /tmp/kc-patched.jar $KCJ   # overwrite in-place

# 5. Reinstall lib/ JARs to local Maven repo (also copies to ~/.m2)
bash setup-local-repo.sh
# If ~/.m2 copy differs, manually copy:
cp lib/kotlin-converter.jar ~/.m2/repository/org/jetbrains/kotlin/kotlin-converter/1.0/kotlin-converter-1.0.jar
```

**Running tests** (must use Java 17 — Java 25 breaks the Kotlin Maven plugin; must use xvfb-run — tests require a display):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk xvfb-run mvn clean test
```

### Plugin Registration
- `src/main/resources/META-INF/layer.xml` — Registers language services, file actions, project integrations
- `@LanguageRegistration` on `KotlinLanguage.java` — Binds the plugin to `.kt` files

## Test Structure

Tests live in `src/test/java/` mirroring feature packages: `completion/`, `diagnostics/`, `formatting/`, `navigation/`, `rename/`, etc.

Test resource files (sample `.kt` files) are in `src/test/resources/projForTest/src/`, organized by feature. Tests extend `KotlinTestCase` (a custom NetBeans test base class) which sets up a mock NetBeans environment.

## Key Versions
- Kotlin compiler (Maven): 1.3.72
- Bundled JARs compiled against: Kotlin 1.1.1 (hence the patches)
- NetBeans target: RELEASE230 (23.0)
- Java source/target: 17
- Java runtime for tests: must use Java 17 (Java 25 breaks the Kotlin Maven plugin's `JavaVersion.parse()`)
