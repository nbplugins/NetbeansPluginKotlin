# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Kotlin plugin for NetBeans IDE** — a JetBrains-developed plugin providing Kotlin language support in NetBeans. **Note**: This project is no longer actively developed (see [issue #122](https://github.com/JetBrains/kotlin-netbeans/issues/122)).

See [docs/development-plan.md](docs/development-plan.md) for the long-term development roadmap.

## Upstream Sources

New and updated source files for the plugin (both plugin code and sources for the bundled JARs) come from the IntelliJ Community repository, available as a git submodule at `submodules/IntellijCommunity` (remote: `git@github.com:oleg68/IntellijCommunity.git`).

To update the submodule to the latest commit:
```bash
git submodule update --remote submodules/IntellijCommunity
```

## Git Workflow

The canonical upstream remote is `https://github.com/nbplugins/NetbeansPluginKotlin.git`.

PRs are submitted from a personal fork (`origin` = `git@github.com:oleg68/NetbeansPluginKotlin.git`).
Always push the feature branch to `origin` (the fork), then open a PR targeting `upstream` (`nbplugins/NetbeansPluginKotlin`).

Branch naming:
- `feature/` — new features (e.g. `feature/a3-mime-type`)
- `bugfix/` — bug fixes (e.g. `bugfix/parser-crash`)
- `refactor/` — refactoring (e.g. `refactor/cleanup-indexer`)
- `doc/` — documentation-only PRs (e.g. `doc/update-readme`)
- `req/MAJOR.MINOR` — release PRs (e.g. `req/0.5`)

Before creating a PR branch, always fetch and sync from the upstream target branch:

```bash
git fetch upstream
git checkout main         # or the target branch
git merge upstream/main   # fast-forward to latest upstream state
git checkout -b <branch>  # then create the feature branch
```

## Release & Versioning

### Versioning scheme

Build version is computed from git tags by CI:
- Base tag `MAJOR.MINOR` (e.g. `0.4`) + commit count from it → `MAJOR.MINOR.N` (e.g. `0.4.13`)
- `pom.xml` holds `MAJOR.MINOR.0-SNAPSHOT` — only MAJOR.MINOR matters to CI; patch and SNAPSHOT suffix are ignored

### Release cycle

A release has an explicit **start** and **finish**:

**Starting a release** (only this, nothing else):
- Bump `pom.xml` to `MAJOR.MINOR.0-SNAPSHOT` → CI creates base tag `MAJOR.MINOR`, build version becomes `MAJOR.MINOR.0`
- Each subsequent push to main → version `MAJOR.MINOR.N` (N increments automatically)

**During development** — add user-visible changes to `CHANGELOG.md` (see rules below).

**Finishing a release** (only this, nothing else):
- Update `CHANGELOG.md` heading to `# MAJOR.MINOR` or `# MAJOR.MINOR (YYYY-MM-DD)` (matching current `pom.xml`) → CI sees this as the release signal, creates release tag `MAJOR.MINOR.N`, and publishes a GitHub Release. If the date is omitted, CI inserts today's date automatically.

**After a published release** — CI auto-edits `CHANGELOG.md`: the release heading `# MAJOR.MINOR (date)` is replaced with `# MAJOR.MINOR.N (date)`. Development can continue immediately; patch versions increment from the last released N.

Implemented in `build-scripts/autotag.sh` and `.github/workflows/build.yml`.

### CHANGELOG.md rules

The heading `# MAJOR.MINOR` or `# MAJOR.MINOR (YYYY-MM-DD)` is the CI release signal — **only add it when finishing a release**. The date is optional; if omitted, CI inserts today's date automatically (e.g. `# 0.4` or `# 0.4 (2026-05-02)`).

During development, add bullet lines at the **very top** of `CHANGELOG.md` (above any existing heading), with no section heading. When finishing a release, add the `# MAJOR.MINOR` or `# MAJOR.MINOR (YYYY-MM-DD)` heading above those bullets.

The changelog within a release is **cumulative**: if a feature was added and later refined or fixed within the same release cycle, **update the existing bullet** rather than adding a new one.

Every user-visible change **must** add or update a bullet at the **top** of the list (reverse chronological order — newest entries first). "User-visible" means: new feature, changed behavior, bug fix (in a previously released version), UI change, new setting, README update. Internal refactors, test-only changes, CI changes, and fixes to features not yet released do not require an entry.

Entries must describe the change from the **user's perspective** — what the user experiences, not how it was implemented.

Each entry must start with a past-tense verb: **Fixed**, **Added**, **Improved**, **Changed**, **Removed**, etc.

Each changelog entry must be committed **together with the code change it describes** — never in a separate commit.

### Commit message rules

Commit messages must also start with a past-tense verb (e.g. "Fixed ...", "Added ..."). The subject line describes *what* was done; the body (if needed) explains *how* or *why*.

When finishing a release by adding `# MAJOR.MINOR` to CHANGELOG.md, the commit message must be `"Requested release MAJOR.MINOR"` (not `"Released MAJOR.MINOR"`).

## Build Commands

All commands run from the **repository root** (multi-module build):

```bash
mvn clean install          # Build all modules and install to local Maven repo
mvn clean package          # Build the plugin (produces .nbm file in Nbm/target/)
mvn test -pl Nbm           # Run all tests
mvn test -pl Nbm -Dtest=ClassName  # Run a single test class
mvn clean package -DskipTests  # Build without running tests
mvn nbm:cluster-app -pl Nbm    # Create a NetBeans test cluster for manual testing
```

The `bundled-jars/*` modules must be built (or have run at least once) before `Nbm` can be built
in isolation. From the root `mvn clean install` handles this automatically.

## Architecture

The plugin integrates with NetBeans via the **CSL (Colored Syntax Language) API** using the MIME type `text/x-kt`. The entry point is `KotlinLanguage.java` which registers all language services.

### Mixed-Language Codebase
- **Java** (`~67 files`): NetBeans integration layer — service registrations, API adapters, and entry points
- **Kotlin** (`~164 files`): Core implementation logic — analysis, completion, refactoring, etc.

### Project Structure

```
pom.xml                  ← root (packaging=pom), dependencyManagement for all versions
Nbm/                     ← main plugin module (packaging=nbm)
  pom.xml
  src/                   ← plugin source and tests
bundled-jars/            ← grouping dir (no pom); each submodule installs one JAR to ~/.m2
  KotlinIdeCommon/
  KotlinFormatter/
  KotlinConverter/
  KotlinCompiler/
  KotlinCompilerIntellijPlatform/
patches/                 ← replacement class sources + message bundles injected at build time
```

### Main Packages (`Nbm/src/main/java/org/jetbrains/kotlin/`)

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

### Bundled JARs

#### bundled-jars/* submodule summary

| Submodule | What it does | To be removed |
|-----------|-------------|---------------|
| **KotlinCompiler** | Takes `kotlin-compiler-1.3.72.jar`, strips everything except `org/jetbrains/kotlin/**`, installs via `install-file` | A4.11 |
| **KotlinCompilerIntellijPlatform** | Takes `kotlin-compiler-1.3.72.jar`, extracts `com/intellij/**`, patches `ContainerUtil`/`StringUtil`/`Extensions`/`AstLoadingFilter`, injects message bundles | A4.11 |
| **KotlinFormatter** | Compiles 12 files from `submodules/Kotlin/idea/formatter/`, patches `ReflectionUtil.copyFields` (inlined) | No — no Maven artifact |
| **KotlinConverter** | Compiles 55+ files from `submodules/Kotlin/j2k/`, patches 2 sites (type inference, `runWriteAction`) | No — no Maven artifact |
| **KotlinIdeCommon** | Compiles all of `submodules/Kotlin/idea/ide-common/src/`, excludes 8 classes overridden by the plugin | No — no Maven artifact |

Several capabilities depend on bundled custom JARs (not from Maven Central):
- `kotlin-ide-common.jar` — JetBrains IDE tooling (compiled from `submodules/Kotlin` sources since A4.7)
- IntelliJ platform core — provided by `com.jetbrains.intellij.platform:core:193.7288.26` +
  `core-impl:193.7288.26` as direct Maven dependencies of Nbm (since A4.10; replaces old `lib/intellij-core-1.0.jar`)

`kotlin-formatter.jar` (A4.3), `kotlin-converter.jar` (A4.6), and `kotlin-ide-common.jar` (A4.7)
are compiled from `submodules/Kotlin` sources and no longer live in `lib/`.

`KotlinCompilerIntellijPlatform` (A4.8): `AstLoadingFilter` and `Extensions` stubs are provided as
source classes in `patches/` (compiled and injected at build time) instead of ASM patches.

Formatter infrastructure (A4.9): `openapi-formatter.jar` and `idea-formatter.jar` replaced by
`com.jetbrains.intellij.platform:code-style:241.194` and `code-style-impl:241.194` (direct Maven
dependencies). All `com.jetbrains.intellij.platform:*` transitive deps are excluded from `Nbm` to
avoid conflicts with bundled 193-era JARs. The following stubs live in `Nbm/src/main/java/`:

| Class | Package | Purpose |
|-------|---------|---------|
| `Configurable` | `com.intellij.openapi.options` | Compile-only; return type of `createSettingsPage()` (throws) |
| `IndentOptionsEditor` | `com.intellij.application.options` | Compile-only; return type of `getIndentOptionsEditor()` (returns null) |
| `CodeStyleSettingsProvider` | `com.intellij.psi.codeStyle` | Runtime; `EXTENSION_POINT_NAME` field needed for extension registration |
| `LanguageCodeStyleSettingsProvider` | `com.intellij.psi.codeStyle` | Runtime; `EP_NAME` field needed for extension registration |
| `CodeStyleSettingsCustomizable` | `com.intellij.psi.codeStyle` | Compile-only interface |
| `CodeStyleSettingsService` | `com.intellij.psi.codeStyle` | Runtime; `getInstance()` returns no-op (empty factory lists) |
| `CustomCodeStyleSettingsManager` | `com.intellij.psi.codeStyle` | Runtime; `getCustomSettings()` uses reflection to create settings |
| `Formatter` | `com.intellij.formatting` | Runtime; `getInstance()` returns `new FormatterImpl()` singleton |
| `DynamicBundle` | `com.intellij` | Runtime; stub for `core:241` i18n bundle (no dynamic plugin support needed) |
| `ConcurrentCollectionFactory` | `com.intellij.concurrency` | Runtime; delegates to `ContainerUtil` (193-era) factory methods |
| `ObjectIntHashMap` | `com.intellij.util.containers` | Runtime; extends `TObjectIntHashMap` AND implements `ObjectIntMap` (241 casts it to interface) |
| `ObjectUtils` | `com.intellij.util` | Runtime; adds `binarySearch(int,int,IntIntFunction)` (needed by `MarkerProduction` in `KotlinCompilerIntellijPlatform`) and `binarySearch(int,int,IntUnaryOperator)` (needed by `code-style-impl:241`); placed in main module JAR to take classloader priority over `ext/util.jar` |

Additionally, `StringUtil` (adds `trimStart(String,String)`) is injected from `util:193.5964` into
`KotlinCompilerIntellijPlatform` — 241-era formatter code calls this method, but the bundled
193-era version lacks it.

These JARs are installed into `~/.m2` automatically by the `bundled-jars/*` Maven modules during
`mvn clean install` from the root. They are installed under `io.github.nbplugins` coordinates
(e.g. `io.github.nbplugins:netbeans-plugin-kotlin-ide-common:${project.version}`).

### JAR Patches (`patches-src/`)

The bundled JARs were compiled against older library versions and require bytecode patches or
class replacements to work with Kotlin 1.3.72 and Java 17+. No ASM patches remain since A4.10.

**Active class replacements** (injected via Ant tasks in `KotlinCompilerIntellijPlatform/pom.xml`):

| What | Source | Why |
|------|--------|-----|
| `ContainerUtil`, `ObjectIntMap/HashMap`, `StringUtil` | extracted from `util:193.5964` | 193.5964 has deprecated methods + `trimStart(String,String)` needed by code-style-impl:241 |
| `ContainerUtilRt`, `AstLoadingFilter`, `Extensions` | compiled from `patches/` sources | kotlin-compiler's embedded versions lack required API |
| `messages/JavaCoreBundle.properties`, `messages/JavaErrorMessages.properties` | `patches/messages/` | absent from `core:193` but required by `LanguageLevel.<clinit>` at runtime |

**Class stripping** — done with Ant tasks in pom.xml:

- `KotlinCompiler`: strips `org/picocontainer/` (replaced by `picocontainer:1.2` Maven dep) and `com/google/` (replaced by `guava:28.2-jre` Maven dep).
- `KotlinIdeCommon`: strips 8 plugin-owned classes (`ReferenceVariantsHelper`, `CallType`, `ExtensionUtils`, `FuzzyType`, `ScopeUtils`, `ShadowedDeclarationsFilter`, `UtilsKt`, `ReceiverType`) — the plugin provides its own versions in `Nbm/src`; bundled copies would conflict at runtime.

**Phase convention for `install-file`** — `KotlinCompilerIntellijPlatform` and `KotlinCompiler` use
`packaging=pom` and bind `maven-install-plugin:install-file` to the **`package`** phase (not
`install`). This allows reactor builds (`mvn clean install` from root) to install patched JARs as
part of `package`, so downstream modules can find them in `~/.m2` before their own `install` phase
runs. The default `install` execution is disabled (`<phase>none</phase>`) to prevent Maven from
overwriting the custom installed JAR with the empty POM-only artifact.

Modules that compile from sources (`KotlinFormatter`, `KotlinConverter`, `KotlinIdeCommon`) use
`packaging=jar` and rely on standard Maven install — no custom `install-file` needed.

**First-time setup** — after a version bump, run `mvn clean install -DskipTests` once to bootstrap
`~/.m2`. Subsequent `mvn clean test` and `mvn clean package` work without install.
`KotlinCompilerIntellijPlatform` and `KotlinCompiler` use `packaging=pom` + `install-file`, so
their JARs must exist in `~/.m2` before downstream modules can resolve them.

**JetBrains Maven repo** (`jetbrains-intellij-releases`) is slow without a proxy. To bootstrap:
download missing 193.x JARs manually via SOCKS5 proxy (`router.oleghome:11337`) using curl and
place them in `~/.m2/repository/com/jetbrains/intellij/platform/<artifact>/<version>/`.

To force-regenerate:

```bash
# Clear cached ~/.m2 entries and rebuild
for art in netbeans-plugin-kotlin-ide-common \
           netbeans-plugin-kotlin-converter netbeans-plugin-kotlin-formatter \
           netbeans-plugin-kotlin-compiler netbeans-plugin-kotlin-compiler-intellij-platform; do
  rm -rf ~/.m2/repository/io/github/nbplugins/$art
done
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean install -DskipTests
```

**Правило разрешения конфликтов версий классов:** При конфликте двух версий одного класса из разных JAR-файлов — всегда стрипить **старую** версию, оставлять **новую**. Если новый код вызывает метод, отсутствующий в старом классе — добавить метод в старый класс (stub в `Nbm/src/main/java/`, главный JAR загружается первым и перекрывает `ext/*.jar`).

**Running tests** (must use Java 17 — Java 25 breaks the Kotlin Maven plugin; Xvfb is started automatically by Maven on display :99):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean test
```

### Plugin Registration
- `Nbm/src/main/resources/META-INF/layer.xml` — Registers language services, file actions, project integrations
- `@LanguageRegistration` on `KotlinLanguage.java` — Binds the plugin to `.kt` files

## Test Structure

Tests live in `Nbm/src/test/java/` mirroring feature packages: `completion/`, `diagnostics/`, `formatting/`, `navigation/`, `rename/`, etc.

Test resource files (sample `.kt` files) are in `Nbm/src/test/resources/projForTest/src/`, organized by feature. Tests extend `KotlinTestCase` (a custom NetBeans test base class) which sets up a mock NetBeans environment.

## NetBeans Runtime Configuration (NB 23+ / Java 17+)

The plugin uses `sun.misc.Unsafe` (via IntelliJ's `AtomicFieldUpdater`) and `java.lang.reflect` APIs that are encapsulated by default in Java 17+. Without the required flags, opening a `.kt` file triggers `ExceptionInInitializerError: Could not initialize class com.intellij.openapi.util.Disposer` and the Kotlin environment never loads.

**Required JVM flags:**
- `-J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED` — reflective access used by `JavaCoreProjectEnvironment`
- `-J--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED` — allows `ReflectionUtil` to call `setAccessible(true)` on `sun.misc.Unsafe.theUnsafe`, which `AtomicFieldUpdater` needs to initialise
- `-J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED` — `DebugReflectionUtil` in `CachedValueChecker` calls `setAccessible(true)` on `AtomicIntegerFieldUpdater.U`; without this, `KotlinParser.parse` fails for every Kotlin file with `InaccessibleObjectException`
- `-J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED` — `sun.misc.Unsafe` in newer JDKs delegates to `jdk.internal.misc.Unsafe.theUnsafe`; without this, `KotlinParser.parse` fails with `InaccessibleObjectException` on `jdk.internal.misc.Unsafe.theUnsafe`

### Option A — пользовательский конфиг (без sudo, рекомендуется)

NB launcher читает `~/.netbeans/<version>/etc/netbeans.conf` после системного и позволяет дополнять настройки:

```bash
mkdir -p ~/.netbeans/27/etc
cat >> ~/.netbeans/27/etc/netbeans.conf << 'EOF'
netbeans_default_options="$netbeans_default_options -J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED -J--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
EOF
```

Replace `27` with your NetBeans major version.

### Option B — системный конфиг (требует sudo)

```bash
sudo sed -i 's|-J--add-opens=java.base/java.lang=ALL-UNNAMED|-J--add-opens=java.base/java.lang=ALL-UNNAMED -J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED -J--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED -J--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED|' \
    /usr/lib/apache-netbeans/etc/netbeans.conf
```

### Проверка

```bash
# пользовательский конфиг
grep "add-opens" ~/.netbeans/27/etc/netbeans.conf

# системный конфиг
grep "jdk.unsupported" /usr/lib/apache-netbeans/etc/netbeans.conf
```

## Key Versions
- Kotlin compiler (Maven): 1.3.72
- Bundled JARs compiled against: Kotlin 1.1.1 (hence the patches)
- NetBeans target: RELEASE230 (23.0)
- Java source/target: 17
- Java runtime for tests: must use Java 17 (Java 25 breaks the Kotlin Maven plugin's `JavaVersion.parse()`)
