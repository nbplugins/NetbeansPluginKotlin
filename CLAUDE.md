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

**Always bump the version with Maven, never by editing pom.xml files manually:**
```bash
mvn versions:set -DnewVersion=0.6.10-SNAPSHOT -DgenerateBackupPoms=false
```
This updates the root pom and all child modules atomically.

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

## Coding Standards

### Package naming

All **new** plugin classes (not existing legacy code) go in `io.github.nbplugins.kotlin.nbm.*`.
Sub-package mirrors the feature area, e.g.:
- `io.github.nbplugins.kotlin.nbm.resolve` — analysis session management
- `io.github.nbplugins.kotlin.nbm.completion` — code completion
- `io.github.nbplugins.kotlin.nbm.diagnostics` — error/warning reporting

### Documentation

Every public class and every public method must have a KDoc (Kotlin) or Javadoc (Java) comment
that explains: purpose, parameters, and return value. Non-obvious private helpers also get a
short comment explaining the *why*.

### Unit tests

Every new class must have a corresponding unit test class.

**Test location and naming mirrors the source tree:**

| Source | Test |
|--------|------|
| `src/main/java/io/github/nbplugins/kotlin/nbm/resolve/Foo.kt` | `src/test/java/io/github/nbplugins/kotlin/nbm/resolve/FooTest.kt` |

Every public method of a new class must have at least one test method in the corresponding test
class. Test classes extend `utils.KotlinTestCase` (or `org.netbeans.junit.NbTestCase` directly
for infrastructure tests that don't need a project).

### MVC separation

Separate concerns into three layers:
- **Model / Service** — analysis logic, data structures; no NetBeans UI APIs.
- **View** — NetBeans nodes, editor annotations, UI panels; no direct analysis calls.
- **Controller** — wires model to view; handles NetBeans lifecycle events.

New classes must be placed in the layer that matches their responsibility.

---

## Pre-commit Checklist

Before every commit, in order:

1. **Run unit tests** — all tests must pass:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean test
   ```

2. **Build the plugin** — must produce a `.nbm` without errors:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean package -DskipTests
   ```

3. **Propose a manual test plan** — based on what changed, list the concrete steps for the user
   to verify in a running NetBeans. Wait for the user to confirm that manual testing passed.

4. **Commit and open PR only after** manual testing is confirmed successful.

---

## Maven Dependency Rules

**All dependency versions must be declared in the root `pom.xml` `<dependencyManagement>` section.**
Never add a `<version>` tag directly in a module `pom.xml` unless it is an explicit override (exception to the default rule), and document why.

**Version policy for multi-version artifacts:** The default version in `dependencyManagement` must be the most current (242-era). Older versions used by specific submodules (e.g., `KotlinConverter` uses `core:232`) are declared explicitly in those submodule pom.xml files as documented exceptions.

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

Running `mvn clean test` or `mvn clean package` from the root reactor builds `bundled-jars/*`
modules first and passes them to `Nbm` automatically — no prior `mvn install` needed.

### Fast iteration (do NOT add `clean` on every build)

The `bundled-jars/CoreImpl` and `bundled-jars/KotlinCompiler` modules repack large
binary JARs (KotlinCompiler unzips/zips ~24k files, 142 MB). An up-to-date guard makes
a **no-clean** rebuild reuse the existing repacked JARs untouched (verified byte-identical):

```bash
# Daily loop while working on Nbm code (C7, etc.) — bundled-jars reused in ~2 s,
# only Nbm recompiles:
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn package -DskipTests

# Use clean ONLY when: a bundled-jar dependency version changed in pom.xml,
# after a git pull touching bundled-jars, or to force a pristine state:
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean package -DskipTests
```

`clean` deletes `target/` (the `repack.stamp` + repacked JAR) → bundled-jars do the
full unzip/strip/jar again. **Habitually typing `mvn clean package` every iteration
defeats the speed-up.** A stale/partial state is always fixable with one `mvn clean package`.

How it works: an Ant `<uptodate>` guard skips the unzip/strip and builds the JAR via
Ant `<jar>` only when inputs changed; `maven-jar-plugin`'s `default-jar` is unbound
(`phase=none`) so it neither re-zips nor scans the 24k files; `gmavenplus-plugin`
points Maven at the JAR so the reactor and `mvn install` resolve the module.

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
bundled-jars/            ← grouping dir (no pom); each submodule produces one JAR
  KotlinIdeCommon/
  KotlinFormatter/
  KotlinConverter/
patches/                 ← replacement class sources for bundled-jars modules (StubBasedPsiElementBase, AtomicFieldUpdater, picocontainer)
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

| Submodule | What it does |
|-----------|-------------|
| **KotlinFormatter** | Compiles 12 files from `submodules/Kotlin/idea/formatter/`, patches `ReflectionUtil.copyFields` (inlined) |
| **KotlinConverter** | Compiles 55+ files from `submodules/Kotlin/j2k/`, patches 2 sites (type inference, `runWriteAction`) |
| **KotlinIdeCommon** | Compiles all of `submodules/Kotlin/idea/ide-common/src/`, excludes 8 classes overridden by the plugin |

Several capabilities depend on bundled custom JARs (not from Maven Central):
- `kotlin-ide-common.jar` — JetBrains IDE tooling (compiled from `submodules/Kotlin` sources since A4.7)
- IntelliJ platform core — provided by `com.jetbrains.intellij.platform:core:193.7288.26` +
  `core-impl:193.7288.26` as direct Maven dependencies of Nbm (since A4.10; replaces old `lib/intellij-core-1.0.jar`)

`kotlin-formatter.jar` (A4.3), `kotlin-converter.jar` (A4.6), and `kotlin-ide-common.jar` (A4.7)
are compiled from `submodules/Kotlin` sources and no longer live in `lib/`.

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
| `DynamicBundle` | `com.intellij` | Runtime; stub for `core:241` i18n bundle — supports both `DynamicBundle(Class,String)` (241-era) and `DynamicBundle(String)` (1.9.25-era) constructors |
| `ConcurrentCollectionFactory` | `com.intellij.concurrency` | Runtime; delegates to `ContainerUtil` (193-era) factory methods |
| `ObjectIntHashMap` | `com.intellij.util.containers` | Runtime; extends `TObjectIntHashMap` AND implements `ObjectIntMap` (241 casts it to interface); adds `containsKey(Object)` absent from 1.9.25 shaded version |
| `ObjectIntMap` | `com.intellij.util.containers` | Runtime; interface stub with `get`, `put`, `containsKey` — 1.9.25 shaded version only has `get`/`put` |
| `ObjectUtils` | `com.intellij.util` | Runtime; adds `binarySearch(int,int,IntUnaryOperator)` (needed by `code-style-impl:241`) and `reachabilityFence(Object)` (no-op, absent in 1.9.25); placed in main module JAR to take classloader priority over `ext/util.jar` |
| `Extensions` | `com.intellij.openapi.extensions` | Runtime; adds `getExtensions(ExtensionPointName)` missing from kotlin-compiler's embedded stub; placed in main module JAR to take classloader priority |
| `MultiMap` | `com.intellij.util.containers` | Runtime; full replacement — 1.9.25 shaded version lacks `createConcurrent()`, `createLinkedSet()`, `createConcurrentSet()`, `isEmpty()`, `containsKey()`, `values()`, `size()`, etc.; uses `java.util.*` instead of missing `CollectionFactory`/`LinkedMultiMap` |
| `FormatTextRanges` | `com.intellij.formatting` | Runtime; replaces code-style-impl:241's version — uses `Collections.sort` instead of `ContainerUtil.sorted(Collection,Comparator)` absent in 1.9.25 |
| `ContainerUtilRt` | `com.intellij.util.containers` | Runtime; copied from `submodules/IntellijCommunity` via generated-sources; kotlin-compiler's embedded version lacks `newArrayList()` |

These JARs are built by the `bundled-jars/*` reactor modules and passed to `Nbm` automatically.
They are installed under `io.github.nbplugins` coordinates
(e.g. `io.github.nbplugins:netbeans-plugin-kotlin-ide-common:${project.version}`).

### JAR Patches

The bundled JARs were compiled against older library versions and require class replacements to
work with Kotlin 1.3.72 and Java 17+. No ASM patches remain since A4.10.

**Active class replacements** — classes in `Nbm/src/main/java/` win over `ext/*.jar` via classloader order:

| What | Source | Why |
|------|--------|-----|
| `StringUtil` | `submodules/IntellijCommunity` (via generated-sources) | kotlin-compiler's embedded version lacks `trimStart(String,String)` needed by code-style-impl:241 |
| `ContainerUtil`, `ContainerUtilRt` | `submodules/IntellijCommunity` (via generated-sources) | kotlin-compiler's embedded versions lack `sorted(Collection,Comparator)` and `newArrayList()` |
| `ObjectIntMap/HashMap` | extracted from `util:193.5964` | 193.5964 has deprecated methods needed at runtime |
| `Extensions` | `Nbm/src/main/java/com/intellij/` | kotlin-compiler's embedded version lacks `getExtensions(ExtensionPointName)` |
| `messages/JavaCoreBundle.properties`, `messages/JavaErrorMessages.properties` | `Nbm/src/main/resources/messages/` | absent from `core:193` but required by `LanguageLevel.<clinit>` at runtime |

**Class stripping** — done with Ant tasks in KotlinIdeCommon/pom.xml:

- `KotlinIdeCommon`: strips 8 plugin-owned classes (`ReferenceVariantsHelper`, `CallType`, `ExtensionUtils`, `FuzzyType`, `ScopeUtils`, `ShadowedDeclarationsFilter`, `UtilsKt`, `ReceiverType`) — the plugin provides its own versions in `Nbm/src`; bundled copies would conflict at runtime.

**JetBrains Maven repo** (`jetbrains-intellij-releases`) is slow without a proxy. To bootstrap:
download missing 193.x JARs manually via SOCKS5 proxy (`router.oleghome:11337`) using curl and
place them in `~/.m2/repository/com/jetbrains/intellij/platform/<artifact>/<version>/`.

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
- Kotlin compiler (Maven): 1.9.25
- NetBeans target: RELEASE230 (23.0)
- Java source/target: 17
- Java runtime for tests: must use Java 17 (Java 25 breaks the Kotlin Maven plugin's `JavaVersion.parse()`)
