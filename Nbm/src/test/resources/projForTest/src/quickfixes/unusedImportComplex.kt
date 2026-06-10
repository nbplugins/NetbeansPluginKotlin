package quickfixes

import java.time.Duration            // used: Duration.ofMillis(...)
import kotlin.math.min               // used: min(...)
import java.io.File                  // unused
import java.util.HashMap             // used as return type

fun complexImportsTest(): HashMap<String, Long> {
    val d = Duration.ofMillis(1)
    val result = HashMap<String, Long>()
    result["x"] = min(1L, d.toMillis())
    return result
}
