package org.jetbrains.kotlin.idea.references

import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtReferenceExpression

/** KtReferenceExpression always has a primary reference in IDEA PSI; stub uses first or throws. */
val KtReferenceExpression.mainReference: KtSimpleNameReference
    get() = references.filterIsInstance<KtSimpleNameReference>().first()

val KtElement.mainReference: KtReference?
    get() = references.filterIsInstance<KtReference>().firstOrNull()
