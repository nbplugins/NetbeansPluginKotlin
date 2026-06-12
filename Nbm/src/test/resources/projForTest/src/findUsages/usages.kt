package findUsages

fun callSites() {
    targetFunction()
    targetFunction()
    val obj = TargetClass()
    obj.targetMethod()
    println(obj.targetProperty)
    println(topLevelProperty)
}
