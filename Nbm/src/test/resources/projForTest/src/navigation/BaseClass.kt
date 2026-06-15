package navigation

open class BaseClass {
    open fun openMethod(): String = "base"
    fun finalMethod(): String = "final"
}

abstract class AbstractBase {
    abstract fun abstractMethod(): Int
    open fun openInAbstract(): String = "abstract-open"
}

interface NavInterface {
    fun interfaceMethod(): Boolean
}
