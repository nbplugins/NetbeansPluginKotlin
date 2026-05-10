- Restored code formatting and auto-indentation: replaced the 1.3.72-era bundled formatter with the official `org.jetbrains.kotlin:formatter:231-1.9.20` binary artifact, eliminating the `NoSuchFieldError` on `KtTokens.FUN_KEYWORD` (B4).
- Upgraded IntelliJ platform core/core-impl/util from 193 to 232, aligning the bundled platform JARs with kotlin-compiler:1.9.25 internals (B3).
- Bumped runtime kotlin-compiler from 1.3.72 to 1.9.25 (B2.0). Bytecode of the Kotlin compiler bundled with the plugin is now ~6 years newer; analysis APIs report richer information.
- Code j2k (Java→Kotlin) and the intentions/quick-fixes that depend on bundled KotlinIdeCommon/KotlinConverter are temporarily non-functional. Those JARs are still compiled against the older 1.3.72 compiler. Tracked for B5/B6.

# 0.5.22 (2026-05-08)

- Compiled kotlin-converter from submodule sources (A4.6)

# 0.5.4 (2026-05-02)

- Changed MIME type from `text/x-kt` to standard `text/x-kotlin`, overriding NetBeans' built-in basic Kotlin support with full CSL-based features

# 0.4.5 (2026-05-02)

- Added GitHub Actions CI/CD workflow (A2)
