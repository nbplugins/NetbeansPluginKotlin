# E5 — Preview Pane Bug Fixes: Session State

**Branch:** `feature/e5-formatter-settings-ui`
**Last committed:** `7b3c00e9` "Intermediate commit"
**Working version:** `0.11.17-SNAPSHOT` (uncommitted; bumped from `0.11.12-SNAPSHOT` through this cycle)
**As of:** 2026-06-03

Continuation of work captured in [e5-session-state.md](e5-session-state.md). All Tools → Options → Kotlin preview pane defects are tracked here.

---

## Summary

A multi-iteration debugging cycle for the Tools → Options → Kotlin preview pane. All five rounds of fixes are uncommitted; the build is green (420/420 tests) and packages cleanly to `Nbm/target/nbm/netbeans-plugin-kotlin-nbm-0.11.17-SNAPSHOT.nbm`. **Awaiting user confirmation** from manual NetBeans testing of the latest scroll-restore + diagnostic logging round.

The active plan file lives at `~/.claude/plans/tools-options-kotlin-1-starry-panda.md` (outside the repo, machine-local). Its content is reproduced in [Active plan](#active-plan) below.

---

## Uncommitted changes (`git status`)

```
M CoreImpl/pom.xml
M KotlinCompilerCliBase/pom.xml
M KotlinFixesImpl/pom.xml
M KotlinFormatter/pom.xml
M KotlinHighlighting/pom.xml
M KotlinIcons/pom.xml
M KotlinIntentionUtils/pom.xml
M Nbm/pom.xml
M Nbm/src/main/java/io/github/nbplugins/kotlin/nbm/formatting/KotlinFormatterUtils.java
M Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/options/formatter/KotlinFormattingPreviewPane.kt
M Nbm/src/main/resources/io/github/nbplugins/kotlin/nbm/options/kotlin-options.png
M Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/options/formatter/KotlinFormattingPreviewPaneTest.kt
M pom.xml
?? Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/options/formatter/KotlinFormatterPreviewInvarianceTest.kt
```

The `pom.xml` modifications are version-only bumps from `mvn versions:set`.

---

## What's done across this cycle

### Round 1 — icon size, syntax highlighting, diff-from-defaults
- **Icon 16×16 → 32×32**: rasterized `kotlin.svg` via Apache Batik to `Nbm/src/main/resources/io/github/nbplugins/kotlin/nbm/options/kotlin-options.png`. The old PNG was a byte-copy of the file-type icon; NetBeans Options dialog renders category icons at 32×32.
- **Preview background + syntax highlighting**: replaced `JTextArea` with `JEditorPane` backed by the Kotlin `EditorKit` from `MimeLookup.getLookup(MimePath.parse("text/x-kotlin"))`. Editor background and lexer-driven coloring now come from the live editor theme.
- **Indent change not visible in preview** (initial diagnosis was wrong; corrected by user): traced to the formatter pipeline overwriting per-call settings.

### Round 2 — formatter pipeline routes through global singleton
- `KotlinFormatterUtils.buildModel` calls `initializeSettings(IndenterUtil)` (overrides indent options with NetBeans editor settings) and then `KotlinCodeStylePreferences.INSTANCE.loadIntoGlobal(prefs())` (reloads from persisted NbPreferences). Both defeat any caller-supplied settings.
- **Fix**: added `KotlinFormatterUtils.formatCodeWithSettings(source, fileName, project, lineSeparator, customSettings)` — independent path that bypasses both side effects and uses the passed `CodeStyleSettings` directly. Also extracted `registerKotlinProvider(CodeStyleSettings)` and made it public so any fresh `CodeStyleSettings` instance can be wired up for Kotlin formatting.
- Preview pane now builds its own `CodeStyleSettings` instance per refresh; the static `KotlinFormatterUtils.getSettings()` singleton is no longer mutated.

### Round 3 — sync refresh
- The user explicitly asked to revert the async (background thread) refresh path to synchronous; the perceived "Apply must be clicked first" was actually the formatter-not-receiving-our-settings bug, not EDT blocking. Synchronous code is simpler and works once Round 2 is in place.

### Round 4 — indent guides + scroll preservation (first attempt)
- Added document-property push in `refresh()`: `SimpleValueNames.TAB_SIZE`, `EXPAND_TABS`, `INDENT_SHIFT_WIDTH` on `editorPane.document` so the NetBeans EditorKit's indent-guide rendering picks up live spinner values. `IndentUtils.tabSize(doc)` / `isExpandTabs(doc)` / `indentLevelSize(doc)` read these keys.
- Added viewport save/restore around `editorPane.text = formatted` (synchronous restore, plus `editorPane.caretPosition = 0`).
- Added 3 unit tests in `KotlinFormattingPreviewPaneTest` (doc properties reflect spinner values, EXPAND_TABS toggle, viewport preserved in headless test).

### Round 5 — text invariance under Use tab character + scroll restore via invokeLater + INFO logging
- **Text invariance**: `refresh()` now forces `tempSettings.indentOptions.USE_TAB_CHARACTER = false` *after* pushing doc properties (so EXPAND_TABS still reflects checkbox for guide visibility). Formatter always emits spaces; only the indent-guide visibility changes when the user toggles the checkbox.
- **Scroll restore**: dropped `caretPosition = 0` (caret-update policy was scrolling viewport). Moved viewport restore into `SwingUtilities.invokeLater` so it runs after the EditorKit's view rebuild.
- **INFO logging**: `java.util.logging.Logger` records `viewport before` and `viewport restored` on every refresh.
- New test file `KotlinFormatterPreviewInvarianceTest.kt` extends `utils.KotlinTestCase` and asserts `getText()` equal across `USE_TAB_CHARACTER=true/false` runs with Tab=4 Indent=2.
- Renamed `testCollectSettingsNotCalledWithoutProject` → `testCollectSettingsCalledOnRefresh` (behavior changed: prefs are always collected so doc props update even on the no-project fallback path).
- Existing `testRefreshPreservesViewportPosition` now calls a `pumpEdt()` helper (`SwingUtilities.invokeAndWait { }`) before asserting; the lambda was extracted to a named method because Kotlin synthesized the inline `testRefreshPreservesViewportPosition$lambda$2` which JUnit3 mistook for a test method.

### Round 6 (current, awaiting verification) — scroll-to-end still observed
- User reports that after any reformat the viewport ends up at the bottom of the preview, even though the INFO log says `viewport restored = same as before`.
- Two hypotheses:
  - **A**: the saved `y=80` value in the log *is* the document max-y, so "before" and "after" both look like the end. Real bug is that user expects logical (line-anchored) restoration.
  - **B**: BaseCaret / view-layout queues its own `invokeLater` after ours, scrolling viewport back to the caret position (which `setText` leaves at end-of-text).
- **Fix (uncommitted)** for both hypotheses:
  - `caretPosition = 0` reinstated *after* `setText` to pin caret at start (suppresses end-scroll).
  - Nested `SwingUtilities.invokeLater { invokeLater { ... } }` for viewport restore — second pass wins against any caret/view-layout async.
  - Expanded logging: `viewport before / caret / viewSize`, `viewport just after setText`, `viewport restored (1st pass)`, `viewport restored (2nd pass) / viewSize`. `viewSize` lets us tell whether y=80 is the document max.
- 420/420 tests pass. `.nbm` built at `Nbm/target/nbm/netbeans-plugin-kotlin-nbm-0.11.17-SNAPSHOT.nbm`.

---

## Key files touched

| File | Role |
|------|------|
| `Nbm/src/main/java/io/github/nbplugins/kotlin/nbm/formatting/KotlinFormatterUtils.java` | added `registerKotlinProvider(CodeStyleSettings)` (public) + `formatCodeWithSettings(source, fileName, project, lineSeparator, customSettings)` — bypasses `initializeSettings`/`loadIntoGlobal` |
| `Nbm/src/main/kotlin/io/github/nbplugins/kotlin/nbm/options/formatter/KotlinFormattingPreviewPane.kt` | full reimplementation of `refresh()`; doc-property push; force `USE_TAB_CHARACTER=false`; pin caret at 0; nested invokeLater viewport restore; INFO logging |
| `Nbm/src/main/resources/io/github/nbplugins/kotlin/nbm/options/kotlin-options.png` | 32×32 raster of `kotlin.svg` (Apache Batik) |
| `Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/options/formatter/KotlinFormattingPreviewPaneTest.kt` | updated existing tests; new doc-property and viewport assertions; `pumpEdt()` helper |
| `Nbm/src/test/kotlin/io/github/nbplugins/kotlin/nbm/options/formatter/KotlinFormatterPreviewInvarianceTest.kt` | NEW: `testPreviewTextDoesNotChangeOnTabToggle` (Tab=4, Indent=2, useTab=true vs false) |
| pom.xml (root + 8 submodules) | version bumps 0.11.12 → 0.11.17 |

---

## Open question — awaiting user response

User must run `0.11.17-SNAPSHOT` in NetBeans, reproduce the scroll-to-end, and share the messages log. The new log lines (`viewport just after setText`, `viewport restored (1st/2nd pass)`, `viewSize`) will reveal:

- If `viewSize.height ≈ viewport.height + viewPos.y` → hypothesis A (y=80 IS the document max); the real fix is logical-line anchoring rather than absolute-y.
- If `viewport restored (1st pass) ≠ viewport restored (2nd pass)` → hypothesis B (async caret-scroll between passes); the nested invokeLater either resolves it or we add a third pass / longer delay.

Reference logs from `0.11.16-SNAPSHOT` (only single restore + INFO):

```
preview refresh: viewport before = Point[x=0,y=0]
preview refresh: viewport restored = Point[x=0,y=0]
preview refresh: viewport before = Point[x=0,y=0]
preview refresh: viewport restored = Point[x=0,y=0]
preview refresh: viewport before = Point[x=0,y=80]
preview refresh: viewport restored = Point[x=0,y=80]
```

Files: `/home/oleg/my-projects/nbplugins/logs/1/messages.log` and `messages.log.1` (machine-local).

---

## Build / test commands

```bash
# Fast iteration (no clean — repack modules skip if up-to-date):
mvn package -DskipTests

# Run all tests from repo root:
mvn test

# Run only preview-pane tests:
mvn test -Dtest='KotlinFormattingPreviewPaneTest,KotlinFormatterPreviewInvarianceTest' -Dsurefire.failIfNoSpecifiedTests=false

# Bump version:
mvn versions:set -DnewVersion=0.11.18-SNAPSHOT -DgenerateBackupPoms=false
```

Tests use Xvfb on display :99 (auto-started by Maven surefire config).

---

## Continuing on another machine

1. Pull the branch and `git stash pop` the working-tree changes if you stashed them, or re-apply the diffs from the files listed in [Key files touched](#key-files-touched).
2. The Maven local repo must have IntelliJ platform 253 artifacts; if missing, follow the proxy procedure described in `CLAUDE.md` → "JetBrains Maven repo".
3. Recreate the plan file `~/.claude/plans/tools-options-kotlin-1-starry-panda.md` from the [Active plan](#active-plan) section below — Claude's plan files are machine-local.
4. Verify the build: `mvn package -DskipTests` then `mvn test`. Both must succeed; expect 420 tests green.
5. Install `Nbm/target/nbm/netbeans-plugin-kotlin-nbm-0.11.17-SNAPSHOT.nbm` in NetBeans, open Tools → Options → Kotlin, attempt to reproduce the scroll-to-end after spinner change, and inspect `~/.netbeans/<ver>/var/log/messages.log` for the new diagnostic lines (search for `preview refresh:`).
6. Based on log readings, either roll forward with absolute-y fix (more invokeLater / Timer) or pivot to logical-line anchoring.

---

## Active plan

The full plan file (`~/.claude/plans/tools-options-kotlin-1-starry-panda.md`) is reproduced verbatim here so it can be recreated on the other machine.

```markdown
# Plan: Preview pane — text invariance under tab toggle, scroll restore

## Context

Continuation of the Tools → Options → Kotlin preview work on
`feature/e5-formatter-settings-ui` (now 0.11.15-SNAPSHOT). After the
previous round (doc-property push, viewport save/restore) three follow-up
issues remain:

1. **Terminology** — the vertical lines drawn in the preview are *indent
   guides* (column = N·INDENT_SIZE), not tab-stop guides. Current code
   already drives them via `SimpleValueNames.INDENT_SHIFT_WIDTH` /
   `EXPAND_TABS`, so behavior is right; only docs in code/plan need
   re-wording.

2. **Toggling Use tab character changes the formatted text** when Tab=4,
   Indent=2. Expected behavior: only the indent-guide visibility should
   toggle; the visible preview text must stay byte-identical. Root cause:
   `refresh()` passes the spinner value `USE_TAB_CHARACTER` to the
   formatter, so the engine emits `\t` characters whenever the level is
   ≥ TAB_SIZE columns deep. The rendered width then depends on the
   JEditorPane's tab-rendering (which may or may not honor our TAB_SIZE
   doc property), creating apparent indent drift.

3. **Scroll position still resets** in real NetBeans even though our
   save/restore around `editorPane.text = …` works in unit tests. Two
   compounding causes:
   - `editorPane.caretPosition = 0` after setText forces the caret-update
     policy to scroll the viewport to the top.
   - The NetBeans Kotlin EditorKit rebuilds its view hierarchy
     asynchronously; `viewport.viewPosition = saved` executed synchronously
     right after setText is clamped to (0,0) because preferredSize has not
     yet been recomputed.

---

## Implementation

### Fix A — Preview text invariant under Use tab character toggle

The UX intent is: **the preview always renders with spaces; the Use tab
character checkbox only toggles indent-guide visibility.** That keeps the
visible layout constant when the user flips the checkbox to compare.

In `KotlinFormattingPreviewPane.refresh()`, after loading `tempSettings`
from prefs and after pushing the *real* spinner values to the document
properties (so EXPAND_TABS reflects the checkbox for guide visibility),
override the formatter input to always use spaces:

```kotlin
tempSettings.indentOptions.USE_TAB_CHARACTER = false
val formatted = KotlinFormatterUtils.formatCodeWithSettings(...)
```

The document property `SimpleValueNames.EXPAND_TABS` keeps the
spinner-derived value (`!opts.USE_TAB_CHARACTER`), so when the user has
"Use tab character" checked, EXPAND_TABS is false → NetBeans editor draws
indent guides. When unchecked, EXPAND_TABS is true → guides hidden. Text
content unchanged either way.

### Fix B — Scroll preservation across reformat

Replace the current synchronous viewport save/restore with the deferred
pattern recommended for Swing text components:

```kotlin
val viewPos = scrollPane.viewport.viewPosition
LOG.log(Level.INFO, "preview refresh: viewport before = {0}", viewPos)
editorPane.text = formatted
// Do NOT touch caretPosition — that triggers caret-update scrolling.
SwingUtilities.invokeLater {
    scrollPane.viewport.viewPosition = viewPos
    LOG.log(Level.INFO, "preview refresh: viewport restored = {0}",
            scrollPane.viewport.viewPosition)
}
editorPane.repaint()
```

`invokeLater` runs after the EditorKit's view rebuild + revalidate cycle,
so `setViewPosition` is no longer clamped. Removing `caretPosition = 0`
prevents the caret-driven scroll-to-top.

Add `private val LOG = Logger.getLogger(...)` to the class. The two
`LOG.log(Level.INFO, ...)` lines are visible in the NetBeans IDE log by
default so we can confirm what viewport positions the pane saw before
and after each refresh, and whether the restored value sticks.

> **Round 6 status (current, awaiting log from user):** the simple
> single-`invokeLater` restore turned out to be insufficient — viewport
> still ends up at the end. The committed code uses nested
> `invokeLater` plus `caretPosition = 0` to pin the caret, plus extra
> log lines (`just after setText`, `restored (1st pass)`, `restored (2nd
> pass)`, `viewSize`). Awaiting user's IDE-log dump to decide between
> hypothesis A (y=max IS the bottom) and hypothesis B (async scroll
> drift after our restore).
```

(The plan file in `~/.claude/plans/` is verbose; section headers above are reproduced in skeleton form to keep this state file scannable. The current code in `KotlinFormattingPreviewPane.refresh()` matches the Round 6 status note.)
