package intentions

fun kaReplaceCallWithBinaryOperator(): Int {
    class Test
    operator fun Test.plus(a: Int): Test = Test()
    val test = Test()
    return test.plus(1)
}
