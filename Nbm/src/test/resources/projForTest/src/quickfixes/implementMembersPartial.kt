package quickfixes

class PartialImpl : PartialInterface {
    override fun someFunc(): Int = 42
}

interface PartialInterface {
    fun someFunc(): Int
    fun otherFunc(): String
}
