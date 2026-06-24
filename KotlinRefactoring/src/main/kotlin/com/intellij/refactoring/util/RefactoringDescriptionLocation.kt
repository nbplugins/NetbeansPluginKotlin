// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.util

import com.intellij.psi.ElementDescriptionLocation

/** Stub for [RefactoringDescriptionLocation]: element description locations not needed in standalone mode. */
class RefactoringDescriptionLocation(val includeParent: Boolean) : ElementDescriptionLocation() {
    companion object {
        @JvmField
        val WITHOUT_PARENT = RefactoringDescriptionLocation(false)
        @JvmField
        val WITH_PARENT = RefactoringDescriptionLocation(true)
    }
}
