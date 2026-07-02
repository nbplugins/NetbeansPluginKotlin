# E9 Refactoring — Undo Caret Manual Test Plan

Plugin: `Nbm/target/netbeans-plugin-kotlin-nbm-<version>.nbm`
Install via: **Tools → Plugins → Downloaded → Add Plugins**, then restart NetBeans.

## Background

Refactorings that edit the document directly (Introduce Variable / Constant / Property /
Type Alias / Import Alias, Extract Function) previously left the caret at the **end of the file**
after a native editor **Ctrl+Z**: the text was reverted correctly, but the caret jumped to EOF.

Root cause: a plain editor Ctrl+Z reverses the recorded document edits and never calls the
Refactoring SPI `undoChange()`; afterwards `EditorCaret` re-positions the caret to the document end.
The fix applies the change as **minimal, targeted edits** and joins a caret-restore edit
(`joinCaretRestoreOnUndo`) to the same atomic undo group, so Ctrl+Z restores the caret to the
position where the refactoring was triggered.

These tests verify, for every affected refactoring, that:
1. the refactoring still produces the correct result (no regression), and
2. after **Ctrl+Z** the text is fully reverted **and the caret returns to the trigger location**
   (near the original expression/selection), **not** the end of the file.

For each case: perform the refactoring → confirm the result → press **Ctrl+Z** once → confirm the
text is back to the original **and** the caret is at/near where you triggered it.

---

## 1. Introduce Variable

**Setup:** a `.kt` file with a repeated expression:

```kotlin
fun demo() {
    println("Hello" + " " + "World")
    println("Hello" + " " + "World")
}
```

**Steps:**
- Put the caret on (or select) `"Hello" + " " + "World"` on the first line → **Introduce Variable**.
- Try both **Replace all occurrences** on and off.
- Refactor, then **Ctrl+Z**.

**Expected:**
- Result: `val value = "Hello" + " " + "World"` inserted; occurrence(s) replaced with `value`
  (with *Replace all* — both lines).
- After Ctrl+Z: original text restored; caret back at the expression on the first line, **not** at EOF.

---

## 2. Introduce Constant

**Setup:**

```kotlin
class C {
    fun f() {
        println(42 * 2)
        println(42 * 2)
    }
}
```

**Steps:**
- Select `42 * 2` → **Introduce Constant**.
- Test both destinations: **top-level** and **companion object** (create one if absent).
- Refactor, then **Ctrl+Z**.

**Expected:**
- Result: `const val CONST = 42 * 2` at the chosen destination; occurrence(s) replaced.
- After Ctrl+Z: original restored; caret back at the selected expression, **not** at EOF.

---

## 3. Introduce Property

**Setup:**

```kotlin
class C {
    fun compute(): Int {
        return 10 + 20
    }
}
```

**Steps:**
- Select `10 + 20` → **Introduce Property** (try `val` and `var`, with/without explicit type).
- Refactor, then **Ctrl+Z**.

**Expected:**
- Result: `val/var NAME = 10 + 20` inserted as a class member; usage replaced with `NAME`.
- After Ctrl+Z: original restored; caret back at the selected expression, **not** at EOF.

---

## 4. Introduce Type Alias

**Setup:**

```kotlin
fun handle(items: Map<String, List<Int>>) {}
fun handle2(items: Map<String, List<Int>>) {}
```

**Steps:**
- Put the caret on the type `Map<String, List<Int>>` → **Introduce Type Alias**.
- Try **Replace all** on and off.
- Refactor, then **Ctrl+Z**.

**Expected:**
- Result: `typealias NAME = Map<String, List<Int>>` inserted; type reference(s) replaced with `NAME`.
- After Ctrl+Z: original restored; caret back at the type reference, **not** at EOF.

---

## 5. Introduce Import Alias

**Setup:**

```kotlin
import java.util.Date

fun a(): Date = Date()
fun b(): Date = Date()
```

**Steps:**
- Trigger **Introduce Import Alias** twice: once with the caret **on the `import` line**, once with
  the caret **on a `Date` usage** in the body.
- Refactor, then **Ctrl+Z**.

**Expected:**
- Result: import becomes `import java.util.Date as ALIAS`; usages replaced with `ALIAS`.
- After Ctrl+Z: original restored; caret back at the trigger location (import line or the usage you
  started from), **not** at EOF.

---

## 6. Extract Function

**Setup:**

```kotlin
fun outer() {
    val a = 1
    val b = 2
    println(a + b)
}
```

**Steps:**
- Select `println(a + b)` (or a larger statement range) → **Extract Function**.
- Refactor, then **Ctrl+Z**.

**Expected:**
- Result: a new `fun extracted(...) { ... }` inserted before the enclosing function; the selection
  replaced with a call.
- After Ctrl+Z: original restored; caret back at the extracted selection / call site, **not** at EOF.

---

## Automated coverage

Run from the repo root:

```bash
mvn test -pl Nbm -Dtest='MinimalDocumentEditsTest,CaretRestoreOnUndoTest'
```

- `MinimalDocumentEditsTest` — targeted document edits produce the expected text, and a single
  compound undo restores the original exactly.
- `CaretRestoreOnUndoTest` — the undo hook runs the (double-deferred) caret restore, is joined only
  to a `CustomUndoDocument`, and is a no-op otherwise.

The editor-caret repositioning after Ctrl+Z depends on a live editor pane and is verified manually
via the steps above.
