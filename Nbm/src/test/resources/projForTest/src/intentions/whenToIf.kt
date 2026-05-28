package intentions

fun test(x: Int): String {
    return when {
        x == 1 -> "one"
        x == 2 -> "two"
        else -> "other"
    }
}
