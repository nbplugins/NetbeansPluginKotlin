- Restored Java-to-Kotlin conversion: recompiled KotlinConverter against the 232-era IntelliJ Community sources, eliminating `TypeFlavorCalculator` and other classes removed between 1.3.72 and 1.9.25 (B6).
- Restored intentions and quick-fixes (Specify Type, Change Return Type, Convert to Sealed Class, Implement Members, etc.): replaced the 1.3.72-era KotlinIdeCommon source module with official `org.jetbrains.kotlin:base-fe10-analysis/code-insight/obsolete-compat/base-psi:231-1.9.20` binary artifacts (B5).
- Restored code formatting and auto-indentation: replaced the 1.3.72-era bundled formatter with the official `org.jetbrains.kotlin:formatter:231-1.9.20` binary artifact, eliminating the `NoSuchFieldError` on `KtTokens.FUN_KEYWORD` (B4).
- Upgraded IntelliJ platform core/core-impl/util from 193 to 232, aligning the bundled platform JARs with kotlin-compiler:1.9.25 internals (B3).
- Bumped runtime kotlin-compiler from 1.3.72 to 1.9.25 (B2.0). Bytecode of the Kotlin compiler bundled with the plugin is now ~6 years newer; analysis APIs report richer information.
# 0.5.22 (2026-05-08)

- Compiled kotlin-converter from submodule sources (A4.6)

# 0.5.4 (2026-05-02)

- Changed MIME type from `text/x-kt` to standard `text/x-kotlin`, overriding NetBeans' built-in basic Kotlin support with full CSL-based features

# 0.4.5 (2026-05-02)

- Added GitHub Actions CI/CD workflow (A2)
