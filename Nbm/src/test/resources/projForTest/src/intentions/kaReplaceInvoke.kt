package intentions

fun kaReplaceInvoke() {
    class Test
    operator fun Test.invoke() {}
    val test = Test()
    test.invoke()
}
