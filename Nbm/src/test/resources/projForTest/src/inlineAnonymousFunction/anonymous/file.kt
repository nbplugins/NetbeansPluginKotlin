fun main() {
    val answer = (<caret>fun(value: Int): Int = value + 1)(41)
    println(answer)
}
