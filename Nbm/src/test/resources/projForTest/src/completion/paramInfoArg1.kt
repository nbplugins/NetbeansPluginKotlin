package completion

fun paramInfoTarget(x: Int, y: String = "hi", vararg z: Boolean) {}

fun testParamInfoArg1() {
    paramInfoTarget(<caret>42, "hi", true)
}
