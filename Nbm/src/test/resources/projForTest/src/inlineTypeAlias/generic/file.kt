package inlineTypeAlias.generic

typealias <caret>Names<T> = List<T>

val strings: Names<String> = listOf("one")
val numbers: Names<Int> = listOf(1)
