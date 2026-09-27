package inlineTypeAlias.simple

typealias <caret>Name = String

fun greet(name: Name) = "Hello, $name"
