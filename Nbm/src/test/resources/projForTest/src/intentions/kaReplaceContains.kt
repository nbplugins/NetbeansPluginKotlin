package intentions

fun kaReplaceContains(): Boolean {
    class Test
    operator fun Test.contains(a: Int): Boolean = true
    val test = Test()
    return test.contains(1)
}
