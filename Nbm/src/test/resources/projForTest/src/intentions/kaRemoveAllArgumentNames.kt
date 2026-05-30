package intentions

fun kaRemoveAllArgumentNames(x: Int, y: Int): Int = x + y

fun use() {
    kaRemoveAllArgumentNames(x = 1, y = 2)
}
