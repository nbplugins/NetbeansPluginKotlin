# Manual Test — Change Kotlin Package

Use the NBM built from the `feature/f4-2-change-kotlin-package` branch. Ensure NetBeans starts
with the required JVM `--add-opens` flags from `CLAUDE.md`.

## 1. Change the active Kotlin file package

1. Create or open a Kotlin project with:
   ```kotlin
   // src/main/kotlin/old/source/Moved.kt
   package old.source

   fun moved() = 1
   ```
2. Create a caller:
   ```kotlin
   // src/main/kotlin/usage/Usage.kt
   package usage

   import old.source.moved

   fun use() = moved()
   ```
3. Open `Moved.kt`, select **Refactor → Change Kotlin Package...**, enter `new.changed`, leave
   **Update Kotlin references** selected, and apply.
4. Verify:
   - `Moved.kt` remains physically at `old/source/Moved.kt`.
   - Its directive is `package new.changed`.
   - `Usage.kt` imports `new.changed.moved` and its call remains valid.
5. Choose **Refactor → Undo Last Refactoring**. Verify both files' exact original text and the
   original physical path are restored.

## 2. Change a recursive folder package in place

1. Create a directory `src/main/kotlin/physical/feature` containing:
   ```kotlin
   // physical/feature/Public.kt
   package old.feature

   fun publicApi() = helper()
   ```
   ```kotlin
   // physical/feature/internal/Helper.kt
   package old.feature.internal

   fun helper() = 1
   ```
2. Add a separate caller importing both declarations.
3. Right-click the **feature** folder and select **Refactor → Change Kotlin Package...**.
4. Enter `new.target`, retain **Update Kotlin references**, and apply.
5. Verify:
   - `Public.kt` is still in `physical/feature` and declares `package new.target`.
   - `Helper.kt` is still in `physical/feature/internal` and declares
     `package new.target.internal`.
   - The caller imports `new.target.publicApi` and `new.target.internal.helper`.
   - No directory was created, deleted, renamed, or moved.
6. Use **Undo Last Refactoring** and verify every package directive and import returns exactly to
   its original state.

## 3. Boundaries and validation

1. Put a non-Kotlin file such as `README.txt` below the selected folder. Repeat the folder test and
   verify it remains unchanged.
2. Try an invalid package such as `not-valid` or `123.start`; verify the dialog blocks refactoring.
3. Add a Java usage, a comment mention, and a string mention of the old FQ name. Verify the dialog
   describes the limitation and those non-Kotlin/non-code occurrences are not claimed as updated.
4. Trigger a known Kotlin name/visibility conflict where feasible. Verify the refactoring does not
   leave partially changed source or import documents.
