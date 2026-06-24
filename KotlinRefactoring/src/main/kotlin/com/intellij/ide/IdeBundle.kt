// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide

/** Stub for [IdeBundle]: returns the message key as the message text in standalone mode. */
object IdeBundle {
    @JvmStatic
    fun message(key: String, vararg params: Any): String = key
}
