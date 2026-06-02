# E5 — Formatter Settings UI: Session State

**Branch:** `feature/e5-formatter-settings-ui`  
**Last commit:** `40aa8924` — Added Kotlin formatter settings UI (E5): Other and Imports panels  
**As of:** 2026-06-02

---

## What is done

### Commits on this branch (newest first)

| Commit | Description |
|--------|-------------|
| `40aa8924` | Added Kotlin formatter settings UI (E5): Other and Imports panels |
| `081dee91` | Started release 0.11; marked E4 done; fixed hardcoded indent in IndenterUtil |

### Rewrite: KotlinCodeStylePreferences (XML serialization)

`KotlinCodeStylePreferences.kt` was rewritten to store settings as XML (diff-from-defaults via
`KotlinCodeStyleSettings.writeExternal` / `readExternal`), instead of flat key/value pairs.

- **No JDOM imports in Nbm.** All JDOM code lives in `KotlinFormatter` JAR where `util-jdom:253`
  is available at compile time.
- Two prefs keys: `PREFS_KEY_KOTLIN = "kotlinCodeStyleSettings"`, `PREFS_KEY_INDENT = "indentOptions"`.
- Delegates to `KotlinCodeStyleSerializer` (new class in KotlinFormatter).

### New class: KotlinCodeStyleSerializer

`KotlinFormatter/src/main/java/io/github/nbplugins/kotlin/formatter/KotlinCodeStyleSerializer.java`

Encapsulates all `org.jdom.*` usage. Static methods:
- `serializeKotlinSettings(CodeStyleSettings)` → XML String (diff-from-defaults)
- `deserializeKotlinSettings(String, CodeStyleSettings)` — reads XML into settings
- `serializeIndentOptions(CodeStyleSettings)` → XML String (`<IndentOptions>` element)
- `deserializeIndentOptions(String, CodeStyleSettings)` — reads XML into indent options

### KotlinCompilerCliBase repack exclusions

`KotlinCompilerCliBase/pom.xml` Groovy script now strips these packages from `kotlin-compiler.jar`:

| Package | Reason |
|---------|--------|
| `it/unimi/dsi/fastutil/` | Provided separately as intellij-deps-fastutil |
| `org/jdom/` | util-jdom:253 has full API; kotlin-compiler copy has stripped constructors |
| `com/intellij/util/xml/dom/` | CoreImpl bundles util-xml-dom:253 with createXmlStreamReader(Reader) |
| `com/fasterxml/aalto/` | aalto-xml:1.3.3 (Nbm compile dep) has construct(ReaderConfig, Reader) |

### KotlinFormatterOtherPanel

`Nbm/.../options/formatter/KotlinFormatterOtherPanel.kt` — `load()`/`store()` rewritten to use
`CodeStyleSettings` as intermediary (no flat keys). Added test-helper accessors:
`isTrailingCommaDeclSelected()`, `isTrailingCommaCallSelected()`, `setTrailingCommaDeclSelected(Boolean)`.

### Tests

All 406 tests pass. New/rewritten tests:
- `KotlinCodeStylePreferencesTest` — 7 tests for XML round-trip (booleans, integers, package tables,
  ALL_OTHER_IMPORTS sentinel, IndentOptions, empty prefs, malformed XML)
- `KotlinFormatterOtherPanelTest` — 3 tests (defaults, round-trip, onChange not called on load)

---

## Agreed UI Architecture (not yet implemented)

Layout identical to IDEA's Kotlin code style panel:

```
┌─────────────────────────────────────────────────────────────┐
│  Style: [Kotlin Official ▾]  [Save...]  [Delete]            │  ← StyleBar (NORTH)
├─────────────────────────────────────────────────────────────┤
│ ┌─ Tabs&Indent │ Spaces │ Wrapping&Braces │ Blank Lines │ Imports │ Other ─┐ │
│ │                                                             │ │
│ │  [controls in JScrollPane]  │  [live preview JEditorPane]  │ │
│ │         ~45%                │           ~55%               │ │
│ └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

- **Spaces tab**: JTree with checkboxes (как в IDEA), not a flat list
- **Wrapping tab**: JTree with JComboBox leaves (как в IDEA)
- **Preview**: per-tab snippet from `KotlinLanguageCodeStyleSettingsProvider.getCodeSample()`; live
  update via `formatPreview()` from `formatUtils.kt` + debounce ~300ms via Swing Timer
- **Tabs&Indent**: also kept in Editors→Formatting (не убирать)
- **Per-project settings**: in project Properties dialog (ProjectCustomizer), NOT in Options panel

---

## Next Steps (priority order)

### Step 1 — StyleBar + KotlinCodeStyleProfileRegistry

JComboBox with built-in styles (Kotlin Official, Obsolete, IDE defaults) + Save/Delete buttons.

Built-in styles use existing IDEA classes:
- `KotlinOfficialStyleGuide` → `CODE_STYLE_DEFAULTS = "KOTLIN_OFFICIAL"`
- `KotlinObsoleteStyleGuide` → `CODE_STYLE_DEFAULTS = "OBSOLETE_KOTLIN_CODING_CONVENTIONS"`
- `null` → IDE defaults

User-defined profiles stored as XML files in `~/.netbeans/VERSION/config/kotlinStyles/<name>.xml`.

New files:
- `Nbm/.../options/formatter/KotlinCodeStyleProfileRegistry.kt` — loads built-in + user XML profiles,
  watches directory for live updates
- `Nbm/.../options/formatter/KotlinStyleBar.kt` — JPanel (NORTH) with JComboBox + Save/Delete buttons

### Step 2 — OptionTreePanel

Reusable pure-Swing JTree with checkbox/combobox renderers written from scratch (no `lang-impl`
dependency). Based on IDEA's `OptionTreeWithPreviewPanel` as reference.

New file: `Nbm/.../options/formatter/OptionTreePanel.kt`

### Step 3 — Remaining 5 formatter panels

All in `io.github.nbplugins.kotlin.nbm.options.formatter`:

| Panel | Controls |
|-------|----------|
| `KotlinFormatterIndentPanel` | Indent size, tab size, use tabs, continuation indent (spinners + checkbox) |
| `KotlinFormatterSpacesPanel` | JTree with 14 boolean checkboxes |
| `KotlinFormatterWrappingPanel` | JTree with JComboBox leaves (Don't wrap / Wrap if long / Wrap always) |
| `KotlinFormatterBlankLinesPanel` | Spinners for blank line counts |
| `KotlinFormatterImportsPanel` | Star-import thresholds + KotlinPackageEntryTable editor |

### Step 4 — KotlinFormattingPreviewPane

`JEditorPane` with Kotlin EditorKit; calls `formatPreview()` from `formatUtils.kt`; debounce 300ms
via Swing Timer.

New file: `Nbm/.../options/formatter/KotlinFormattingPreviewPane.kt`

### Step 5 — Restructure KotlinOptionsPanel

Replace current `JTabbedPane`-only with:
- NORTH: StyleBar
- CENTER: JSplitPane(JTabbedPane | KotlinFormattingPreviewPane)

File: `Nbm/.../options/KotlinOptionsPanel.kt`

### Step 6 — Per-project ProjectCustomizer

Reads/writes `.idea/codeStyles/Project.xml` (if `.idea/` exists) or `.kotlin-code-style.xml`.
Registered in project Properties dialog via `ProjectCustomizer`.

### Step 7 — CHANGELOG + PR

---

## Key Files

| File | Role |
|------|------|
| `KotlinFormatter/src/main/java/io/github/nbplugins/kotlin/formatter/KotlinCodeStyleSerializer.java` | XML serialize/deserialize (all JDOM code here) |
| `Nbm/.../formatting/options/KotlinCodeStylePreferences.kt` | Prefs bridge — delegates to Serializer, no JDOM |
| `Nbm/.../options/KotlinOptionsPanelController.kt` | OptionsPanelController for Tools→Options→Kotlin |
| `Nbm/.../options/KotlinOptionsPanel.kt` | Root panel (to be restructured with StyleBar + split + preview) |
| `Nbm/.../options/formatter/KotlinFormatterOtherPanel.kt` | "Other" tab (trailing comma checkboxes) |
| `Nbm/.../reformatting/formatUtils.kt` | `formatPreview()` for project-less preview |
| `KotlinCompilerCliBase/pom.xml` | Groovy repack script with classpath exclusions |

---

## Stop-and-Discuss Rules

**CRITICAL:** Do NOT make decisions on the following without discussing first:

- Any new dependency added to any pom.xml
- Any change to layer.xml or NBM module registration
- Architecture of OptionTreePanel (JTree rendering approach)
- How ProjectCustomizer integrates with NB project API
- Any change to KotlinAnalysisAPISession or FakeIntellijHome
- Any blocker not listed above

---

## Build Commands

```bash
# Fast iteration (no clean — repack modules skip if up-to-date):
mvn package -DskipTests

# Run all tests:
mvn test -pl Nbm

# Run a single test:
mvn test -pl Nbm -Dtest=KotlinCodeStylePreferencesTest

# Full clean build:
mvn clean package -DskipTests
```

Tests require Xvfb on display :99 (started automatically by Maven surefire config).
