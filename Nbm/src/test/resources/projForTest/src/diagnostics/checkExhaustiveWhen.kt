package diagnostics

enum class Color { RED, GREEN, BLUE }

fun checkExhaustiveWhen(c: Color): Int = when (c) {
    Color.RED -> 1
    Color.GREEN -> 2
}
