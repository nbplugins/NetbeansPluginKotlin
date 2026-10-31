package searchusages

open class Base {
    open fun foo() {}
}

open class Middle : Base() {
    override fun foo() {}
}

class Leaf : Middle() {
    override fun foo() {}
}
