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
  IntellijCore/
  OpenapiFormatter/
  IdeaFormatter/
lib/                     ← patched JARs (referenced by bundled-jars/* modules)
patches-src/             ← ASM-based patch tools (used to produce the PATCHED JARs)
patches/                 ← replacement class sources for picocontainer/IntelliJ utilities
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

### Bundled JARs (`lib/`)
Several capabilities depend on bundled custom JARs (not from Maven Central):
- `kotlin-ide-common.jar`, `kotlin-formatter.jar`, `kotlin-converter.jar` — JetBrains IDE tooling
- `intellij-core.jar` — IntelliJ platform core used for Kotlin analysis
- `openapi-formatter.jar`, `idea-formatter.jar` — Formatting infrastructure

These JARs are installed into `~/.m2` automatically by the `bundled-jars/*` Maven modules during
`mvn clean install` from the root. They are installed under `io.github.nbplugins` coordinates
(e.g. `io.github.nbplugins:netbeans-plugin-kotlin-ide-common:${project.version}`).

### JAR Patches (`patches-src/`)

The bundled JARs were compiled against older library versions and require bytecode patches to
work with Kotlin 1.3.72 and Java 17+. Patches are written using ASM and live in `patches-src/`.

| Patch | Input JAR | Problem fixed |
|-------|-----------|---------------|
| `InjectGetGreenStub.java` | `lib/intellij-core-1.0.jar` | Adds `getGreenStub()` to `SubstrateRef` and `StubBasedPsiElementBase` — method missing from the bundled IntelliJ version but called by kotlin-compiler 1.3.72 |
| `PatchStripBundled.java` | `lib/intellij-core-1.0.jar` | Strips `com/intellij/psi/search/SearchScope.class` — kotlin-compiler 1.3.72 has a newer version with `contains(VirtualFile)`; the old one in intellij-core causes `NoSuchMethodError` |
| `PatchKtNodeTypes.java` | `kotlin-compiler-1.3.72.jar` (Maven) | Fixes `KtNodeTypes.BODY` field declared as `KtNodeType` but accessed as `IElementType` — Java 17+ enforces strict descriptor matching |
| `PatchStripBundled.java` | `kotlin-compiler-1.3.72.jar` (Maven) | Strips `org/picocontainer/` — the bundled old picocontainer is incomplete; replaced by `picocontainer:1.2` Maven dependency which is a strict superset |
| `PatchStripBundled.java` | `kotlin-compiler-1.3.72.jar` (Maven) | Strips `com/google/` — the bundled Guava lacks `Maps.newHashMap()`; replaced by `guava:28.2-jre` Maven dependency |
| `PatchStripBundled.java` | `kotlin-compiler-1.3.72.jar` (Maven) | Strips `com/intellij/openapi/extensions/Extensions.class` and `impl/` — kotlin-compiler bundles a newer incompatible `Extensions`/`ExtensionsAreaImpl`; `intellij-core`'s older but internally consistent versions must win |
| `PatchImportConversionKt.java` | `lib/kotlin-converter-1.0.jar` | Rewrites `ImportConversionKt` static initializer to use `JvmPlatformAnalyzerServices` instead of the removed `JvmPlatform.getDefaultImports()` |
| `PatchFqnPart.java` | `lib/kotlin-converter-1.0.jar` | Renames `ImportPath.fqnPart()` → `getFqName()` (renamed in 1.3.72); redirects `AddToStdlibKt.singletonList()` → `Collections.singletonList()` (removed in 1.3.72) |
| `PatchKotlinIdeCommon.java` | `lib/kotlin-ide-common-1.0.jar` | Removes `setShowInternalKeyword` call (property removed in 1.3.72); renames `KotlinTypeFactory.simpleType(5-arg)` → `simpleTypeWithNonTrivialMemberScope`; redirects `KotlinType.isError()` → static `KotlinTypeKt.isError(KotlinType)` |
| `PatchFormatterBody.java` | `lib/kotlin-formatter-1.0.jar` | Rewrites `getstatic KtNodeTypes.BODY` descriptor from `KtNodeType` → `IElementType` — Java 17+ enforces strict field descriptor matching at resolution |

**Applying patches** (run with Java 17):

```bash
cd /path/to/KotlinNetbeans
CP="patches-src:/usr/share/java/objectweb-asm/asm.jar"

# Compile patch tools (only needed once, or after editing patches-src/)
javac -cp /usr/share/java/objectweb-asm/asm.jar patches-src/*.java -d patches-src

# 1. Patch intellij-core.jar
#    NOTE: intellij-core-1.0-PATCHED.jar is committed to git with many pre-applied strips.
#    To rebuild from scratch, apply all strips documented in git history. For the incremental
#    strip of ConcurrentRefHashMap/ConcurrentWeakHashMap (added to fix Disposer init failure):
java -cp $CP PatchStripBundled lib/intellij-core-1.0-PATCHED.jar /tmp/ic-new.jar \
    com/intellij/util/containers/ConcurrentRefHashMap \
    com/intellij/util/containers/ConcurrentWeakHashMap
cp /tmp/ic-new.jar lib/intellij-core-1.0-PATCHED.jar
#    Full rebuild from raw:
java -cp $CP InjectGetGreenStub lib/intellij-core-1.0.jar /tmp/ic-step1.jar
#    ... then apply all strips from original committed PATCHED jar (see git log), then:
java -cp $CP PatchStripBundled /tmp/ic-stepN.jar lib/intellij-core-1.0-PATCHED.jar \
    com/intellij/util/containers/ConcurrentRefHashMap \
    com/intellij/util/containers/ConcurrentWeakHashMap

# 2. Patch kotlin-converter.jar (PatchImportConversionKt -> PatchFqnPart)
java -cp $CP PatchImportConversionKt lib/kotlin-converter-1.0.jar /tmp/kconv-step1.jar
java -cp $CP PatchFqnPart /tmp/kconv-step1.jar lib/kotlin-converter-1.0-PATCHED.jar

# 3. Patch kotlin-ide-common.jar
java -cp $CP PatchKotlinIdeCommon lib/kotlin-ide-common-1.0.jar lib/kotlin-ide-common-1.0-PATCHED.jar

# 4. Patch kotlin-formatter.jar
java -cp $CP PatchFormatterBody lib/kotlin-formatter-1.0.jar lib/kotlin-formatter-1.0-PATCHED.jar

# 5. Patch kotlin-compiler-1.3.72.jar from Maven (KtNodeTypes -> strip picocontainer -> strip Guava)
#    The original in ~/.m2 is NOT modified; output goes to lib/ as a versioned artifact.
KCJ=~/.m2/repository/org/jetbrains/kotlin/kotlin-compiler/1.3.72/kotlin-compiler-1.3.72.jar
java -cp $CP PatchKtNodeTypes $KCJ /tmp/kc-step1.jar
java -cp $CP PatchStripBundled /tmp/kc-step1.jar /tmp/kc-step2.jar org/picocontainer/
java -cp $CP PatchStripBundled /tmp/kc-step2.jar /tmp/kc-step3.jar com/google/
java -cp $CP PatchStripBundled /tmp/kc-step3.jar lib/kotlin-compiler-1.3.72-PATCHED.jar \
    com/intellij/openapi/extensions/Extensions.class \
    com/intellij/openapi/extensions/impl/
# lib/kotlin-compiler-1.3.72-PATCHED.pom is committed (carries transitive dep declarations)

# 6. Reinstall lib/ JARs — rebuild from root to re-run bundled-jars/* install-file executions:
mvn clean install -DskipTests
# Or clear specific ~/.m2 entries and rebuild:
for art in netbeans-plugin-kotlin-intellij-core netbeans-plugin-kotlin-ide-common \
           netbeans-plugin-kotlin-converter netbeans-plugin-kotlin-formatter \
           netbeans-plugin-kotlin-compiler; do
  rm -rf ~/.m2/repository/io/github/nbplugins/$art
done
```

**Правило разрешения конфликтов версий классов:** При конфликте двух версий одного класса из разных JAR-файлов — всегда стрипить **старую** версию, оставлять **новую**. При необходимости патчить **точки вызова** (call sites) через ASM, чтобы они использовали API новой версии.

**Note on bundled library conflicts:** `kotlin-compiler-1.3.72.jar` bundles old/incomplete versions of
several libraries (`org/picocontainer/`, `com/google/`, `com/intellij/openapi/extensions/`) that
conflict with the versions required by `intellij-core.jar` call sites. The bundled copies are stripped
so that Maven dependencies (`picocontainer:1.2`, `guava:28.2-jre`) or the `intellij-core` copy
(`Extensions`) are the sole providers.
`intellij-core-1.0.jar` bundles an old `SearchScope` without `contains(VirtualFile)`; it is stripped
so that kotlin-compiler's newer version is loaded instead.
`intellij-core-1.0.jar` also bundles old `ConcurrentRefHashMap` and `ConcurrentWeakHashMap` that call
`ContainerUtil.newConcurrentMap(int,float,int,TObjectHashingStrategy)` — a method removed from the
newer `ContainerUtil` in `kotlin-compiler-1.3.72.jar`. Both are stripped from `intellij-core` so that
kotlin-compiler's self-consistent newer versions are loaded instead.

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
- `-J--add-opens=java.base/java.lang.reflect=ALL-UNNAMED` — reflective access used by `JavaCoreApplicationEnvironment`
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
