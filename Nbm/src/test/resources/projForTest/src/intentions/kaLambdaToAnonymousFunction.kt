package intentions

fun kaLambdaToAnonymousFunction(list: List<Int>): List<Int> {
    return list.filter({ x -> x > 0 })
}
