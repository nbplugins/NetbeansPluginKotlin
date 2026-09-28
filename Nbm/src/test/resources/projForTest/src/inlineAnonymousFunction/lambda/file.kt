fun main() {
    val answer = (<caret>{ value: Int -> value + 1 })(41)
    println(answer)
}
