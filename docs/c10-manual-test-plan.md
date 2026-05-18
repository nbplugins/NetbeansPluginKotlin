# C10 Manual Test Plan — Remove K1/FE10/BindingContext

Plugin: `Nbm/target/nbm/netbeans-plugin-kotlin-nbm-0.7.40-SNAPSHOT.nbm`  
Install via: **Tools → Plugins → Downloaded → Add Plugins**

C10 removed all K1 fallback code (`KotlinEnvironment`, `BindingContext`,
`AnalysisResultWithProvider`, `KotlinCacheServiceImpl`). Every feature now runs
exclusively through the K2 Analysis API. The tests below verify that nothing
regressed and that the K2-only paths work correctly.

---

## 1. Syntax Highlighting

**Setup:** Open any `.kt` file.

**Expected:**
- Keywords (`fun`, `val`, `var`, `class`, `if`, `return`, …) — **bold blue**
- String literals — default string color
- Single-line (`//`) and multi-line (`/* */`) comments — comment color
- KDoc tags (`@param`, `@return`) — bold italic, underlined

---

## 2. Semantic Highlighting (K2 path)

**Setup:** Open a `.kt` file with the following content:

```kotlin
import java.io.File

@Suppress("unused")
class MyService<T>(val file: File) {

    fun process(input: String): T? {
        val localVar = input.trim()
        return null
    }
}
```

**Expected colors** (synchronized with IntelliJ default light theme):
- `MyService` (class name) — default text color
- `T` (type parameter) — **teal `#20999D`**
- `file` (field/member) — **purple `#660E7A` bold**
- `File` (class reference) — default text color
- `Suppress` (annotation) — **olive `#808000`**
- `input`, `localVar` — default text color (parameters/locals not specially colored)

---

## 3. Error Diagnostics

**Setup:** Create a `.kt` file with a type error:

```kotlin
val x: Int = "not an int"
```

**Expected:** Red underline on `"not an int"` with error message in the tooltip.

---

## 4. Code Completion

**Setup:** Open a `.kt` file in a project with Kotlin SDK. Type:

```kotlin
fun main() {
    "hello".
}
```

Place caret after the `.` and trigger completion (Ctrl+Space).

**Expected:** String methods (`length`, `trim`, `uppercase`, `split`, …) appear in the popup.

---

## 5. Hover Tooltip / Documentation

**Setup:** Open a `.kt` file with:

```kotlin
fun greet(name: String) = "Hello, $name"

fun main() {
    greet("World")
}
```

**Steps:**
1. Hover over `greet` in the `main` function body.

**Expected:** Tooltip shows the function signature (`fun greet(name: String): String`).

---

## 6. Go-to-Declaration (Ctrl+Click)

**Setup:** Same file as above.

**Steps:**
1. Ctrl+Click on `greet` in the `main` function.

**Expected:** Caret jumps to the `greet` function declaration.

---

## 7. Structure View (Navigator)

**Setup:** Open a `.kt` file with a class:

```kotlin
class Calculator {
    val result: Int = 0
    fun add(a: Int, b: Int) = a + b
    fun subtract(a: Int, b: Int) = a - b
}
```

**Steps:**
1. Open **Window → Navigator** (or Ctrl+7).

**Expected:** Navigator panel shows `Calculator` with members `result`, `add`, `subtract`.

---

## 8. Code Folding

**Setup:** Open a `.kt` file with a multi-line function and a class body.

**Expected:**
- Fold controls (`+` / `−`) appear in the gutter next to function bodies and class bodies.
- Clicking `−` collapses the block; clicking `+` expands it.

---

## 9. Hints / Intentions

**Setup:** Open a `.kt` file with:

```kotlin
class Empty()
class WithEmptyBody {}
```

**Steps:**
1. Place caret on `Empty()` → Alt+Enter.
2. Place caret inside `{}` of `WithEmptyBody` → Alt+Enter.

**Expected:**
- Hint "Remove empty primary constructor" for `Empty()`
- Hint "Remove empty class body" for `WithEmptyBody`

**Verify:** Applying each hint produces the corrected code without a crash.

---

## 10. No KotlinCacheService Crash

**Setup:** Open a `.kt` file and wait for the hints task to run (a few seconds after
the file opens).

**Expected:** No `IllegalStateException: Cannot find service KotlinCacheService` in
the NetBeans log (`~/.netbeans/<version>/var/log/messages.log`). The hints light bulb
appears normally.

---

## 11. Replace Size Check Intention (K2 implementation)

**Setup:** File with:

```kotlin
val list = listOf(1, 2, 3)
if (list.size > 0) println("not empty")
```

**Steps:**
1. Caret on `size > 0`.
2. Alt+Enter.

**Expected:** Intention "Replace 'size > 0' check with 'isNotEmpty()'" appears.  
**Apply:** Result is `if (list.isNotEmpty()) println("not empty")`.

---

## 12. Project Open — No KotlinEnvironment Error

**Steps:**
1. Close and reopen a Kotlin project.
2. Open a `.kt` file.

**Expected:** No `KotlinEnvironment`-related exception in the log. K2 session
initialises normally (log contains `KotlinAnalysisAPISession` startup messages).

---

## Pass Criteria

All 12 tests pass with no exceptions in `messages.log` related to
`KotlinCacheService`, `KotlinEnvironment`, `BindingContext`, or
`AnalysisResultWithProvider`.
