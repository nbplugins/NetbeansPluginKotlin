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

Before creating a branch for a PR, always sync from the upstream target branch:

```bash
git fetch upstream
git checkout main         # or the target branch
git merge upstream/main   # fast-forward to latest upstream state
git checkout -b <branch>  # then create the feature branch
```

The canonical upstream remote is `https://github.com/nbplugins/NetbeansPluginKotlin.git`.

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

# 1. Patch intellij-core.jar (InjectGetGreenStub -> strip old SearchScope)
java -cp $CP InjectGetGreenStub lib/intellij-core-1.0.jar /tmp/ic-step1.jar
java -cp $CP PatchStripBundled /tmp/ic-step1.jar lib/intellij-core-1.0-PATCHED.jar \
    com/intellij/psi/search/SearchScope

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
# lib/kotlin-compiler-1.3.72-PATCHED.pom is committed and used by setup-local-repo.sh

# 6. Reinstall lib/ JARs to local Maven repo
bash setup-local-repo.sh
# Clear ~/.m2 copies so Maven picks up fresh versions from repo/:
for jar in intellij-core kotlin-ide-common kotlin-converter kotlin-formatter; do
  rm -rf ~/.m2/repository/org/jetbrains/kotlin/$jar/1.0-PATCHED
done
rm -rf ~/.m2/repository/org/jetbrains/kotlin/kotlin-compiler/1.3.72-PATCHED
```

**Note on bundled library conflicts:** `kotlin-compiler-1.3.72.jar` bundles old/incomplete versions of
several libraries (`org/picocontainer/`, `com/google/`, `com/intellij/openapi/extensions/`) that
conflict with the versions required by `intellij-core.jar` call sites. Rather than patching call
sites, the bundled copies are stripped so that Maven dependencies (`picocontainer:1.2`,
`guava:28.2-jre`) or the `intellij-core` copy (`Extensions`) are the sole providers.
`intellij-core-1.0.jar` bundles an old `SearchScope` without `contains(VirtualFile)`; it is stripped
so that kotlin-compiler's newer version is loaded instead.

**Running tests** (must use Java 17 — Java 25 breaks the Kotlin Maven plugin; Xvfb is started automatically by Maven on display :99):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk mvn clean test
```

### Plugin Registration
- `src/main/resources/META-INF/layer.xml` — Registers language services, file actions, project integrations
- `@LanguageRegistration` on `KotlinLanguage.java` — Binds the plugin to `.kt` files

## Test Structure

Tests live in `src/test/java/` mirroring feature packages: `completion/`, `diagnostics/`, `formatting/`, `navigation/`, `rename/`, etc.

Test resource files (sample `.kt` files) are in `src/test/resources/projForTest/src/`, organized by feature. Tests extend `KotlinTestCase` (a custom NetBeans test base class) which sets up a mock NetBeans environment.

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
