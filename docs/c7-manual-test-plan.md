# C7 Manual Test Plan — K2 Hints/Intentions/Fixes

Plugin: `Nbm/target/nbm/netbeans-plugin-kotlin-nbm-0.7.15-SNAPSHOT.nbm`
Install via: **Tools → Plugins → Downloaded → Add Plugins**

---

## 1. Specify Type Explicitly

**Setup:** Open/create a `.kt` file with:
```kotlin
val x = 42
fun foo() = "hello"
```

**Steps:**
1. Place caret on `x` (or `foo`)
2. Wait for hint / Alt+Enter

**Expected:** Hint "Specify return type explicitly" / "Specify type explicitly" appears  
**Apply:** Verify result is `val x: Int = 42` / `fun foo(): String = "hello"`

---

## 2. Remove Explicit Type

**Setup:** File with:
```kotlin
val x: Int = 42
```

**Steps:**
1. Place caret on `x`
2. Alt+Enter

**Expected:** Hint "Remove explicit type specification"  
**Apply:** Verify result is `val x = 42`

---

## 3. Convert to Block Body

**Setup:** File with:
```kotlin
fun f() = 42
```

**Steps:**
1. Caret on `f`
2. Alt+Enter

**Expected:** Hint "Convert to block body"  
**Apply:** Verify result is:
```kotlin
fun f(): Int {
    return 42
}
```

---

## 4. Convert to Expression Body

**Setup:** File with:
```kotlin
fun f(): Int {
    return 42
}
```

**Steps:**
1. Caret on `f`
2. Alt+Enter

**Expected:** Hint "Convert to expression body"  
**Apply:** Verify result is `fun f() = 42`

---

## 5. Implement Members Quick Fix

**Setup:** File with:
```kotlin
interface Greeter {
    fun greet(): String
}

class Hello : Greeter  // red underline
```

**Steps:**
1. Click on `Hello` (red underline)
2. Alt+Enter

**Expected:** Quick fix "Implement members"  
**Apply:** Verify stub:
```kotlin
class Hello : Greeter {
    override fun greet(): String {
        TODO("Not yet implemented")
    }
}
```

---

## 6. Replace Size Check with isNotEmpty

**Setup:** File with:
```kotlin
val list = listOf(1, 2, 3)
if (list.size > 0) println("not empty")
```

**Steps:**
1. Caret on `size`
2. Alt+Enter

**Expected:** Hint "Replace size check with isNotEmpty()"  
**Apply:** Verify → `if (list.isNotEmpty()) println("not empty")`

---

## 7. K1 Fallback (Regression Check)

1. Open a `.kt` file in a project **without** Kotlin SDK configured
2. Verify hints still appear (K1 fallback path, no crash)

---

## 8. Unused Imports

**Setup:** File with:
```kotlin
import java.util.ArrayList
import java.io.File

fun main() {
    val f = File("test")
}
```

**Expected:** `import java.util.ArrayList` shows warning / grayed out  
**Apply fix:** Verify unused import is removed
