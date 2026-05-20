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
