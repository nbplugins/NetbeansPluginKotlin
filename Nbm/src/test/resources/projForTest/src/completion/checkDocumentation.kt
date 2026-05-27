package completion

/**
 * A function with KDoc for hover tests.
 *
 * @param x the input integer
 * @return the string representation of [x]
 */
fun functionWithKDoc(x: Int): String = x.toString()

fun functionWithoutKDoc(): Unit {}

fun testHover() {
    val result = functionWithKDoc(42)
    functionWithoutKDoc()
}
