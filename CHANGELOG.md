- Added Kotlin formatter settings UI under Tools → Options → Kotlin: three tabs ("Tabs & Indent", "Blank Lines", "Other"), a style-preset selector (Kotlin Coding Conventions / Kotlin Obsolete Coding Conventions / IDE Defaults), and a live preview pane that reformats a Kotlin code sample as settings are changed; the formatter picks up all these settings on the next reformat.
- Added Kotlin to the language list in Tools → Options → Editors → Formatting with a "Tabs and Indents" tab. The preview pane updates live as indent size, tab size, and expand-tabs are changed, reflecting the actual Kotlin formatter output.
- Fixed hardcoded 4-space indent in the Kotlin formatter: indent size, tab size, and use-tabs option are now read from the standard NetBeans editor preferences (Tools → Options → Editor → Formatting → Language: Kotlin), so the Kotlin formatter respects per-language settings set in the global "All Languages" tab or overridden per-language.

# 0.10.12 (2026-05-30)

- Fixed semantic highlighting being completely absent for Kotlin files where K2 symbol resolution crashes on a single element (e.g. due to FIR phase ordering in projects targeting JVM 1.8): before-resolve highlights (class/function/property/parameter names, annotation entries) are now always applied, and K2-resolve highlights accumulate up to the failing element instead of being discarded entirely.
- Added 17 K2 quick-fixes matching IDEA: modifier fixes (add `@JvmInline`, add `val`/`var` to constructor parameter, add `suspend`, add `noinline`/`crossinline`, convert object with constructor to class, add return expression), expression fixes (remove extra argument, convert to function invocation, move nested type alias to top level, convert char literal to string), supertype/constructor fixes (remove supertype, remove constructor invocation), property fixes (add getter/setter accessors, add explicit type annotation to parameter), and `when` fix (add remaining branches).
- Fixed "Unresolved reference" errors in Maven projects where `kotlin-maven-plugin` lists source directories as plain relative paths (e.g. `src/main/kotlin`) in `<sourceDirs>` without the `${project.basedir}` prefix: those paths are now correctly resolved against the project base directory.
- Added 5 modifier/visibility intentions: add `open` modifier to a final function or property in an open/abstract/sealed/enum class; and four "Make public/private/protected/internal" intentions that replace or remove the explicit visibility keyword respecting implicit visibility defaults and override constraints.
- Added 6 expression/operator intentions: apply De Morgan's law on `&&`/`||`, convert operator to explicit function call (`a + b` → `a.plus(b)`), flip binary expression operands (`a > b` → `b < a`), insert/remove explicit type arguments, and specify explicit lambda parameter signature.
- Added 30 intentions: 7 when/if refactoring intentions (add remaining `when` branches, convert `if`-else chain ↔ `when`, flatten nested `when`, merge/split `if`s, invert `if` condition), 6 string transformation intentions (convert concatenation ↔ string template, convert to raw `"""` literal, convert concatenation or template to `buildString { }`), 8 lambda/function-reference intentions (convert lambda ↔ callable reference `::`, lambda → anonymous function, lambda body single-line ↔ multi-line, `.forEach {}` ↔ `for` loop), 5 property/declaration intentions (convert property getter ↔ initializer, add getter/setter stubs, split property declaration into declaration + assignment, move body property into primary constructor parameter), and 4 braces intentions (add/remove braces on a single branch, add/remove braces on all branches of an if-chain or when expression).
- Added parameter info popup (Ctrl+P): pressing Ctrl+P inside a Kotlin function call now shows the function's parameter list with the current parameter highlighted.
- Improved time to first semantic highlighting for a Kotlin file: removed a global scan-in-progress check that blocked the parser while any project was being indexed, added background pre-warming of the K2 analysis session when a `.kt` editor opens so session initialisation runs in parallel with NetBeans project scanning, and applied semantic highlighting in the background for `.kt` files already open when NetBeans starts (restored from the previous session) so colours appear without requiring a click on the tab.

# 0.9.13 (2026-05-27)

- Added hover tooltip: pausing the mouse over a Kotlin symbol now shows a documentation popup with a syntax-highlighted signature, container information, and KDoc sections; each completion candidate also shows its own documentation rather than always showing the first candidate's docs.
- Improved code completion: dot-receiver (`expr.`) now shows only members and applicable extensions of the receiver type; each item displays its signature (`(param: Type): ReturnType` or `: Type`); Kotlin-accurate icons distinguish val, var, method, extension function, interface, enum, object, type alias, and parameter; items are deduplicated and sorted with locals and type members first.
- Added automatic indentation of code pasted into the `.kt` editor: both single-line and multi-line pastes are shifted to the correct indentation level at the insertion point; the internal relative indentation of the pasted block is preserved (no reformatting).
- Added Format option to the `.kt` editor right-click context menu (after Refactor); when text is selected, only the selected range is reformatted (Alt+Shift+F already worked; this adds the menu entry and range support).
- Fixed `SEVERE: ClassNotFoundException: org.codehaus.plexus.util.PropertyUtils` logged on J2SE project open: replaced the inaccessible Maven Embedder utility with a standard `Properties.load()` call.
- Added History tab to `.kt` file editor: file-change history is now accessible directly from the editor alongside the Source tab.
- Removed empty "Visual" tab from `.kt` file editor; only the "Source" tab (and "History" when the versioning module is installed) now appears.

# 0.9.5 (2026-05-25)

- Added IntelliJ IDEA-style Kotlin highlighting settings: Tools > Options > Fonts & Colors now lists the same named Kotlin categories as IDEA (keywords, numbers, string escapes, classes, properties, function calls, smart casts, labels, etc.) with IDEA's default colors for light and dark themes, plus an IDEA-matching preview sample.
- Fixed K2 semantic highlighting drifting out of place while typing unsaved changes: colors below the edit point now stay correctly positioned as you type.

# 0.8.15 (2026-05-22)

# 0.8.6 (2026-05-20)

- Fixed JDK standard library types not visible in the K2 analysis session, causing false type errors and broken semantic highlighting for code that uses JDK types.
- Added support for Kotlin 2.3.x source files.

# 0.7.13 (2026-05-19)

- Switched all language features (diagnostics, completion, semantic highlighting, hints/quick-fixes, navigation) to the K2 Analysis API, replacing the previous K1 engine.
- Added support for Kotlin 2.0.x source files.
- Added code folding for Kotlin files: collapse/expand (+/-) controls in the editor gutter for the import list, comments and code blocks, with the fold types listed in Tools > Options > Editor > Folding.
- Added K2 Navigator panel support: classes, functions and properties in `.kt` files are now listed in the Navigator panel using the Analysis API.

# 0.6.8 (2026-05-12)

- Upgraded maximum supported Kotlin version from 1.3 to 1.9.

# 0.5.22 (2026-05-08)

- Compiled kotlin-converter from submodule sources (A4.6)

# 0.5.4 (2026-05-02)

- Changed MIME type from `text/x-kt` to standard `text/x-kotlin`, overriding NetBeans' built-in basic Kotlin support with full CSL-based features

# 0.4.5 (2026-05-02)

- Added GitHub Actions CI/CD workflow (A2)
