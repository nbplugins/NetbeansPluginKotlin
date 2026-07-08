// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.changeSignature

import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.usageView.UsageInfo

/**
 * Real IDEA's `fromJavaChangeInfo` (in the excluded `KotlinJavaChangeInfoConverter.kt`) bridges a
 * Java-side signature change onto the equivalent Kotlin one via `JavaChangeInfoConverters`. Java
 * interop is out of scope for this port (E9.8), so only the [KotlinChangeInfoBase] identity branch
 * survives; a non-Kotlin [changeInfo] (which never happens standalone — this plugin only ever
 * constructs [KotlinChangeInfo]) returns null.
 */
fun fromJavaChangeInfo(changeInfo: ChangeInfo, usageInfo: UsageInfo, beforeMethodChange: Boolean): KotlinChangeInfoBase? {
    return changeInfo as? KotlinChangeInfoBase
}
