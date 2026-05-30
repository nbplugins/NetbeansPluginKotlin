package intentions

fun kaRemoveSingleArgumentName(x: Int, y: Int): Int = x + y

fun use() {
    kaRemoveSingleArgumentName(x = 1, y = 2)
}
