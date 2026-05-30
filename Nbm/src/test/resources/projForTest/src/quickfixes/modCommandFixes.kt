// Test fixture with multiple quick-fix scenarios
package quickfixes

// TOO_MANY_ARGUMENTS: remove extra arg
fun twoParams(a: Int, b: Int) = a + b
val callTooMany = twoParams(1, 2, 3)

// NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY
fun missingReturn(): Int {
    val x = 1
}

// DATA_CLASS_NOT_PROPERTY_PARAMETER
data class DataMissingVal(name: String)

// CONSTRUCTOR_IN_OBJECT
object ObjWithCtor {
    constructor()
}

// MANY_CLASSES_IN_SUPERTYPE_LIST
open class A
open class B
class Multi : A(), B()

// NO_ELSE_IN_WHEN
sealed class Sealed { class One : Sealed(); class Two : Sealed() }
fun whenNoElse(s: Sealed): Int = when (s) {
    is Sealed.One -> 1
}

// TOPLEVEL_TYPEALIASES_ONLY
class Container {
    typealias Inner = String
}

// FUNCTION_CALL_EXPECTED
val notAFun: Int = 42
val callNotFun = notAFun()

// VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE
fun paramNoType(x = 42) {}

// USAGE_IS_NOT_INLINABLE — for KaAddInlineModifierFix
inline fun inlineHost(action: () -> Unit) {
    val captured = action
    captured()
}

// VALUE_CLASS_WITHOUT_JVM_INLINE_ANNOTATION — for KaAddJvmInlineFix
value class Wrap(val v: Int)
