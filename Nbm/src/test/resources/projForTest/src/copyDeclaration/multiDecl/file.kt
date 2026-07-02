package copyDeclaration.multiDecl

import java.io.File

fun helper(): String = "helper"

fun processFile(path: String): String {
    val f = File(path)
    return f.absolutePath
}
