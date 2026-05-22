package semantic

fun topLevelFun() {}
fun String.extensionFun() {}
suspend fun suspendFun() {}

fun callSites() {
    topLevelFun()
    "hello".extensionFun()
}
