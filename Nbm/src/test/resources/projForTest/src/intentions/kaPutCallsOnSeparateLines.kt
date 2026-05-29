package intentions

fun kaPutCallsOnSeparateLines(list: List<Int>): List<Int> {
    return list.filter { it > 0 }.map { it * 2 }
}
