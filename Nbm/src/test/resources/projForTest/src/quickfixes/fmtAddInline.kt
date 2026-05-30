package quickfixes
inline fun fmtAddInline(action: () -> Unit) {
    val captured = action
    captured()
}
