package completion

fun topLevelNonExtension() {}
fun String.stringExtension(): String = this
fun funcWithParams(x: Int, y: String): Boolean = true
val myProp: String = ""
var myVar: Int = 0

fun testAfterDot() {
    val s = "hello"
    s.<caret>
}
