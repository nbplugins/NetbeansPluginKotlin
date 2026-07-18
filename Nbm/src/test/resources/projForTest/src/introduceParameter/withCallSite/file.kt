package introduceParameter.withCallSite

fun greet(): String {
    return "Hello, " + "world"
}

fun caller(): String {
    return greet()
}
