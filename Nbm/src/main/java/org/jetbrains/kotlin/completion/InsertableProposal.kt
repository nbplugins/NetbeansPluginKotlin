package org.jetbrains.kotlin.completion

import javax.swing.text.Document

/** Completion proposal that can insert its text into a [Document] on acceptance. */
interface InsertableProposal {
    fun doInsert(document: Document)
}
