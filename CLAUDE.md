# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Kotlin plugin for NetBeans IDE** — an actively maintained fork of the original JetBrains plugin (abandoned in 2020, see [issue #122](https://github.com/JetBrains/kotlin-netbeans/issues/122)) published at [nbplugins/NetbeansPluginKotlin](https://github.com/nbplugins/NetbeansPluginKotlin). The fork migrates the plugin to the K2 Analysis API, updates all bundled dependencies, and adds modern editor features (semantic highlighting, code completion, hover docs, etc.).

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
Sub-package mirrors the feature area. Current packages:
- `io.github.nbplugins.kotlin.nbm.completion` — code completion proposals
- `io.github.nbplugins.kotlin.nbm.diagnostics` — error/warning detection and reporting
- `io.github.nbplugins.kotlin.nbm.file` — file-type utilities
- `io.github.nbplugins.kotlin.nbm.filesystem` — virtual filesystem integration
- `io.github.nbplugins.kotlin.nbm.formatting` — code formatting adapter
- `io.github.nbplugins.kotlin.nbm.highlighter` — syntax and semantic token coloring
- `io.github.nbplugins.kotlin.nbm.hints` — K2 hints, intentions, quick-fixes
- `io.github.nbplugins.kotlin.nbm.hover` — hover tooltip / documentation popup
- `io.github.nbplugins.kotlin.nbm.indentation` — auto-indent on paste
- `io.github.nbplugins.kotlin.nbm.installer` — module install/upgrade hooks
- `io.github.nbplugins.kotlin.nbm.j2k` — Java-to-Kotlin conversion stub
- `io.github.nbplugins.kotlin.nbm.language` — language registration and configuration
- `io.github.nbplugins.kotlin.nbm.model` — shared data models (e.g. highlight token types)
- `io.github.nbplugins.kotlin.nbm.navigation` — go-to-definition, find usages
- `io.github.nbplugins.kotlin.nbm.options` — Tools→Options→Kotlin panel controllers and root panel
- `io.github.nbplugins.kotlin.nbm.options.formatter` — individual formatter panels (Indent, Spaces, Wrapping, BlankLines, Imports, Other), StyleBar, SchemeManager, OptionTreePanel, preview pane, project customizer
- `io.github.nbplugins.kotlin.nbm.projectsextensions` — Maven/Gradle/Ant project integration
- `io.github.nbplugins.kotlin.nbm.reformatting` — reformat-selection support
- `io.github.nbplugins.kotlin.nbm.resolve` — K2 analysis session management
- `io.github.nbplugins.kotlin.nbm.startup` — pre-warming and startup tasks
- `io.github.nbplugins.kotlin.nbm.structurescanner` — Navigator panel (K2 structure scanner)
- `io.github.nbplugins.kotlin.nbm.utils` — shared helpers

### Documentation

Every public class and every public method must have a KDoc (Kotlin) or Javadoc (Java) comment
that explains: purpose, parameters, and return value. Non-obvious private helpers also get a
short comment explaining the *why*.

### Unit tests

Every new class must have a corresponding unit test class.

**Test location and naming mirrors the source tree:**

| Source | Test |
|--------|------|
| `src/main/kotlin/io/github/nbplugins/kotlin/nbm/resolve/Foo.kt` | `src/test/kotlin/io/github/nbplugins/kotlin/nbm/resolve/FooTest.kt` |

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

1. **Add copyright headers to any new files** — run:
   ```bash
   python3 build-scripts/update-copyright.py --all
   ```
   This adds the canonical header (JetBrains + nbplugins) to every `.java`/`.kt` file
   in `Nbm/src/main/java`, `Nbm/src/main/kotlin`, `Nbm/src/test/java`, and `Nbm/src/test/kotlin` that has no copyright. Safe to run
   repeatedly (idempotent). Verify with `python3 build-scripts/update-copyright.py --check`.

2. **Run unit tests** — all tests must pass:
   ```bash
   mvn clean test
   ```

3. **Build the plugin** — must produce a `.nbm` without errors:
   ```bash
   mvn clean package -DskipTests
   ```

4. **Propose a manual test plan** — based on what changed, list the concrete steps for the user
   to verify in a running NetBeans. Wait for the user to confirm that manual testing passed.

5. **Commit and open PR only after** manual testing is confirmed successful.

---

## Maven Dependency Rules

**All dependency versions must be declared in the root `pom.xml` `<dependencyManagement>` section.**
Never add a `<version>` tag directly in a module `pom.xml` unless it is an explicit override (exception to the default rule), and document why.

**Version policy for multi-version artifacts:** The default version in `dependencyManagement` must be the most current (253-era). Older versions used by specific submodules are declared explicitly in those submodule pom.xml files as documented exceptions.

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

Running `mvn clean test` or `mvn clean package` from the root reactor builds the bundled-JAR
modules first and passes them to `Nbm` automatically — no prior `mvn install` needed.

### Fast iteration (do NOT add `clean` on every build)

The `CoreImpl` and `KotlinCompilerCliBase` modules repack large
binary JARs (KotlinCompilerCliBase unzips/zips ~24k files, 142 MB). An up-to-date guard makes
a **no-clean** rebuild reuse the existing repacked JARs untouched (verified byte-identical):

```bash
# Daily loop while working on Nbm code — repack modules reused in ~2 s,
# only Nbm recompiles:
mvn package -DskipTests

# Use clean ONLY when: a bundled-jar dependency version changed in pom.xml,
# after a git pull touching repack modules, or to force a pristine state:
mvn clean package -DskipTests
```

`clean` deletes `target/` (the `repack.stamp` + repacked JAR) → repack modules do the
full unzip/strip/jar again. **Habitually typing `mvn clean package` every iteration
defeats the speed-up.** A stale/partial state is always fixable with one `mvn clean package`.

How it works: a Groovy `<uptodate>` guard in `gmavenplus-plugin` skips extraction when
`repack.stamp` is newer than the source JARs; `maven-jar-plugin`'s `default-jar` is
unbound (`phase=none`) so it neither re-zips nor scans the 24k files; the plugin points
Maven at the pre-built JAR so the reactor and `mvn install` resolve the module.

## Architecture

The plugin integrates with NetBeans via the **CSL (Colored Syntax Language) API** using the MIME type `text/x-kotlin`. The entry point is `KotlinLanguage.java` which registers all language services.

### Mixed-Language Codebase
- **Java** (`~67 files`): NetBeans integration layer — service registrations, API adapters, and entry points
- **Kotlin** (`~164 files`): Core implementation logic — analysis, completion, refactoring, etc.

### Project Structure

```
pom.xml                  ← root (packaging=pom), dependencyManagement for all versions
Nbm/                     ← main plugin module (packaging=nbm)
  pom.xml
  src/                   ← plugin source and tests
CoreImpl/                ← bundled JAR: repacked IntelliJ platform core+core-impl+util (193/253)
KotlinCompilerCliBase/   ← bundled JAR: CLI subset of kotlin-compiler (~24k entries, 142 MB)
KotlinFormatter/         ← bundled JAR: Kotlin formatter compiled from submodules/IntellijCommunity
KotlinHighlighting/      ← bundled JAR: K2 semantic highlighting compiled from submodules/IntellijCommunity
KotlinIcons/             ← bundled JAR: Kotlin completion icons (SVG→PNG via Batik)
patches/                 ← replacement class sources for bundled modules (StubBasedPsiElementBase, AtomicFieldUpdater, picocontainer)
```

### Main Packages

**Legacy packages** (`Nbm/src/main/java/org/jetbrains/kotlin/`) — original JetBrains code, kept as-is:

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

**New packages** (`Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/`) — reworked and new classes (see *Package naming* under Coding Standards for the full sub-package list).

### Bundled JARs

Several capabilities depend on bundled custom JARs (not from Maven Central).
Active reactor modules (at repo root alongside `Nbm/`):

| Module | Artifact | Purpose |
|--------|----------|---------|
| `CoreImpl` | `netbeans-plugin-kotlin-core-impl` | Repacked `core-impl:193` with stripped/replaced classes; classloader glue for IntelliJ platform |
| `KotlinCompilerCliBase` | `netbeans-plugin-kotlin-compiler-cli-base` | Repacked `kotlin-compiler` (heavy — ~24k entries, 142 MB); only CLI classes absent from the `-for-ide` thin artifacts |
| `KotlinFormatter` | `netbeans-plugin-kotlin-formatter` | Kotlin formatter compiled from `submodules/IntellijCommunity` |
| `KotlinHighlighting` | `netbeans-plugin-kotlin-highlighting` | Kotlin semantic highlighting pipeline (`FunctionCallHighlighter`, `TypeHighlighter`, `VariableReferenceHighlighter`) compiled from `submodules/IntellijCommunity` |
| `KotlinIcons` | `netbeans-plugin-kotlin-icons` | Kotlin-accurate completion icons (val, var, extension function, etc.) converted from IntelliJ SVG sources to 16×16 PNG at build time via Apache Batik |

Other bundled capabilities (not separate reactor modules):
- IntelliJ platform core — provided by `com.jetbrains.intellij.platform:core:193.7288.26` +
  `core-impl:193.7288.26` as direct Maven dependencies of Nbm (since A4.10; replaces old `lib/intellij-core-1.0.jar`)

Formatter infrastructure (A4.9): `openapi-formatter.jar` and `idea-formatter.jar` replaced by
`com.jetbrains.intellij.platform:code-style:253.33514.17` and `code-style-impl:253.33514.17` (direct Maven
dependencies). All `com.jetbrains.intellij.platform:*` transitive deps are excluded from `Nbm` to
avoid conflicts with bundled JARs. The following stubs live in `Nbm/src/main/java/`:

| Class | Package | Purpose |
|-------|---------|---------|
| `Configurable` | `com.intellij.openapi.options` | Compile-only; return type of `createSettingsPage()` (throws) |
| `IndentOptionsEditor` | `com.intellij.application.options` | Compile-only; return type of `getIndentOptionsEditor()` (returns null) |
| `CodeStyleSettingsProvider` | `com.intellij.psi.codeStyle` | Runtime; `EXTENSION_POINT_NAME` field needed for extension registration |
| `LanguageCodeStyleSettingsProvider` | `com.intellij.psi.codeStyle` | Runtime; `EP_NAME` field needed for extension registration |
| `CodeStyleSettingsCustomizable` | `com.intellij.psi.codeStyle` | Compile-only interface |
| `CodeStyleSettingsService` | `com.intellij.psi.codeStyle` | Runtime; `getInstance()` returns no-op (empty factory lists) |
| `CustomCodeStyleSettingsManager` | `com.intellij.psi.codeStyle` | Runtime; `getCustomSettings()` uses reflection to create settings in standalone mode |
| `Formatter` | `com.intellij.formatting` | Runtime; `getInstance()` returns `new FormatterImpl()` singleton (253-era `getInstance()` returns null in standalone mode) |
| `ConcurrentCollectionFactory` | `com.intellij.concurrency` | Runtime; `concurrency:253` module not published — provides `createConcurrentIdentityMap()` etc. needed by code-style-impl |
| `ConcurrencyUtil` | `com.intellij.util` | Runtime; `computeIfAbsent(UserDataHolder, Key, Supplier)` absent from `util:253` but called by analysis-api:2.3.21 |
| `Registry` | `com.intellij.openapi.util.registry` | Runtime; stripped from CoreImpl (see CoreImpl/pom.xml); exposes `Companion` inner class so analysis-api:2.3.21 Kotlin code can access `Registry.Companion` |
| `RegistryValue` | `com.intellij.openapi.util.registry` | Runtime; used by the `Registry` stub above |
| `Editor` | `com.intellij.openapi.editor` | Compile-only; referenced by `NetBeansFormattingModel` parameter type |
| `CodeInsightContextManager` | `com.intellij.codeInsight.multiverse` | Runtime; Kotlin interface stub overriding `core:253` version; adds `isSharedSourceSupportEnabled(): Boolean = false` default method absent from 253 interface but called via `invokeinterface` by `kotlin-compiler-ir-for-ide:2.3.21` (compiled against 253) |
| `KotlinSettingsProvider` | `com.intellij.formatting` | Plugin-specific; extends `CodeStyleSettingsProvider`; provides `KotlinCodeStyleSettings` factory |
| `KotlinLanguageCodeStyleSettingsProvider` | `com.intellij.formatting` | Plugin-specific; extends `LanguageCodeStyleSettingsProvider`; provides Kotlin code style settings UI |

These JARs are built by the reactor modules above and passed to `Nbm` automatically.
They are installed under `io.github.nbplugins` coordinates
(e.g. `io.github.nbplugins:netbeans-plugin-kotlin-core-impl:${project.version}`).

### JAR Patches

The bundled JARs were compiled against older library versions and require class replacements to
work with the current runtime (Kotlin 2.3.21, Java 17+). No ASM patches remain since A4.10.

**Active class replacements** — classes in `Nbm/src/main/java/` win over `ext/*.jar` via classloader order:

| What | Source | Why |
|------|--------|-----|
| `messages/JavaCoreBundle.properties`, `messages/JavaErrorMessages.properties` | `Nbm/src/main/resources/messages/` | absent from `core:253` but required by `LanguageLevel.<clinit>` at runtime |

**JetBrains Maven repo** (`jetbrains-intellij-releases`) is slow without a proxy. To bootstrap:
download missing JARs manually via SOCKS5 proxy (`router.oleghome:11337`) using curl and
place them in `~/.m2/repository/com/jetbrains/intellij/platform/<artifact>/<version>/`.

**Правило разрешения конфликтов версий классов:** При конфликте двух версий одного класса из разных JAR-файлов — всегда стрипить **старую** версию, оставлять **новую**. Если новый код вызывает метод, отсутствующий в старом классе — добавить метод в старый класс (stub в `Nbm/src/main/java/`, главный JAR загружается первым и перекрывает `ext/*.jar`).

**Running tests** (Xvfb is started automatically by Maven on display :99):

```bash
mvn clean test
```

### Plugin Registration
- `Nbm/src/main/resources/org/jetbrains/kotlin/layer.xml` — Registers language services, file actions, project integrations (316 lines)
- `Nbm/src/main/resources/org/jetbrains/kotlin/navigation/layer.xml` — Navigation-specific layer entries
- `@LanguageRegistration` on `KotlinLanguage.java` — Binds the plugin to `.kt` files

### Why `-proc:none` and why layer.xml is hand-written

`Nbm/pom.xml` compiles Java sources with `-proc:none` (disables all JSR-269 annotation processing).
This is a deliberate consequence of the mixed Kotlin+Java build:

1. `kotlin-maven-plugin` (phase `process-sources`) compiles **all** sources — both Kotlin and Java.
2. `maven-compiler-plugin` (phase `compile`) re-processes remaining Java files. At this point `.kt`
   source files are invisible to javac. If any annotated Java class inherits a Kotlin type, the
   NetBeans annotation processor cannot resolve it and fails with a type error.

`-proc:none` prevents the processors from running at step 2 entirely.

**Consequence:** NetBeans annotation processors (`LanguageRegistrationProcessor`,
`MimeRegistrationProcessor`, `ServiceProviderProcessor`) never run, so no `generated-layer.xml` is
produced. All layer entries are written manually in `layer.xml` (see *Plugin Registration* above). The files contain
`// normally generated by LanguageRegistrationProcessor…` comments marking every entry that would
have been auto-generated if annotation processing were enabled.

**Annotations in source** (`@LanguageRegistration`, `@MimeRegistration`, `@ServiceProvider`,
`@Messages`) are kept as documentation of intent and for compile-time API compatibility checks —
they do **not** generate any registrations at build time.

**Cannot be fully automated:** Kotlin classes annotated with `@ServiceProvider` (e.g.,
`KotlinSemanticHighlightsLayerFactory.kt`) are not visible to javac processors even without
`-proc:none`, so at least those entries would always require a manual layer.xml entry. Partial
automation (Java-only classes) is possible but not worth the added complexity.

## Test Structure

Tests live in `Nbm/src/test/kotlin/` mirroring feature packages: `completion/`, `diagnostics/`, `formatting/`, `navigation/`, `rename/`, etc.

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
- Kotlin compiler plugin (`kotlin.compile.version`): 2.2.21
- Kotlin bundled runtime (`kotlin.runtime.version`): 2.3.21 — `kotlin-compiler-ir-for-ide:2.3.21` (unshaded) + Analysis API 2.3.21 (D3+D5+D7 complete)
- Kotlin language/API version (`kotlin.runtime.languageVersion`): 2.2 (capped until context-receivers → context-parameters migration)
- IntelliJ Platform era: 253 (`253.33514.17`; D7 complete — platform bumped from 242; analysis-api stays at 2.3.21 because `registerDefaultComponents` added only in ij253 IDE-internal build)
- NetBeans target: RELEASE230 (23.0)
- Java source/target: 17
- **K2 Analysis API**: all language features run exclusively via `KaSession` / `analyze {}` (C10 complete — K1/FE1.0/BindingContext code removed)

## K2-Only Architecture (post-C10)

As of C10, all K1 fallback code (`KotlinEnvironment`, `BindingContext`, `AnalysisResultWithProvider`,
`KotlinCacheServiceImpl`, `resolve/lang/java/`, etc.) has been removed. Every feature runs exclusively
through the K2 Analysis API (`StandaloneAnalysisAPISession`).

**Known workarounds in place:**
- `Registry.java` stub in `Nbm/src/main/java/com/intellij/openapi/util/registry/` exposes a `Companion`
  inner class so that `analysis-api:2.3.21` code accessing `Registry.Companion` at runtime finds the
  expected field. The stub takes classloader precedence over the Kotlin `Registry` in `KotlinCompilerCliBase`.
- `KotlinAnalysisAPISession` adds the JDK home as a `KtSdkModule` dependency so that
  JDK standard library types are visible in the analysis session.
- `CodeInsightContextManager.kt` stub in `Nbm/src/main/kotlin/` overrides the `core:253` interface to
  add `isSharedSourceSupportEnabled(): Boolean = false` — called via `invokeinterface` by
  `kotlin-compiler-ir-for-ide:2.3.21` (compiled against 253 where this method existed).
