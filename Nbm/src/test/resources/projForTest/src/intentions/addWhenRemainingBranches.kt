package intentions

enum class Dir { North, South, East, West }

fun test(d: Dir) = when (d) {
    Dir.North -> 1
}
