package completion

fun topLevelNonExtension() {}
fun String.stringExtension(): String = this

fun testAfterDot() {
    val s = "hello"
    s.<caret>
}
