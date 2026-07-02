package copyDeclaration.withImports

import java.io.File
import java.util.Date

fun processFile(path: String): String {
    val f = File(path)
    return f.absolutePath
}
