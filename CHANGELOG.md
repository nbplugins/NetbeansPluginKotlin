- Added code folding for Kotlin files: collapse/expand (+/-) controls in the editor gutter for the import list, comments and code blocks, with the fold types listed in Tools > Options > Editor > Folding.
- Added K2 Navigator panel support: classes, functions and properties in `.kt` files are now listed in the Navigator panel using the Analysis API.
- Added K2 Analysis API session infrastructure (`KotlinAnalysisAPISession`) alongside the existing K1 path; both analysis backends are now available simultaneously

# 0.6.8 (2026-05-12)

- Upgraded maximum supported Kotlin version from 1.3 to 1.9.

# 0.5.22 (2026-05-08)

- Compiled kotlin-converter from submodule sources (A4.6)

# 0.5.4 (2026-05-02)

- Changed MIME type from `text/x-kt` to standard `text/x-kotlin`, overriding NetBeans' built-in basic Kotlin support with full CSL-based features

# 0.4.5 (2026-05-02)

- Added GitHub Actions CI/CD workflow (A2)
