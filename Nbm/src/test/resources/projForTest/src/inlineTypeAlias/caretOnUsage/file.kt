package inlineTypeAlias.caretOnUsage

typealias Name = String

fun greet(name: <caret>Name) = "Hello, $name"
