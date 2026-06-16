package navigation

class DerivedClass : BaseClass() {
    override fun openMethod(): String = "derived"
}

class ConcreteImpl : AbstractBase() {
    override fun abstractMethod(): Int = 42
    override fun openInAbstract(): String = "concrete"
}

class InterfaceImpl : NavInterface {
    override fun interfaceMethod(): Boolean = true
}
