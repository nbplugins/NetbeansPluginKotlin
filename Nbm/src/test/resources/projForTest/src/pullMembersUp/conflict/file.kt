open class Base {
    fun greet(): String = "base"
}

class Child : Base() {
    fun greet(): String = "child"
}
