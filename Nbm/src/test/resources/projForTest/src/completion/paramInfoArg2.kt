package completion

fun paramInfoTarget2(x: Int, y: String = "hi", vararg z: Boolean) {}

fun testParamInfoArg2() {
    paramInfoTarget2(42, <caret>"hi", true)
}
