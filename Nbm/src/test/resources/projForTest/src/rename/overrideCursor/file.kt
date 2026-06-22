package rename.overrideCursor

interface Shape {
    fun area(): Double
}

class Circle : Shape {
    override fun area(): Double = 3.14
}

class Square : Shape {
    override fun area(): Double = 4.0
}

fun printArea(s: Shape) = println(s.area())
