/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package com.intellij.codeInspection.dataFlow.types

import com.intellij.codeInspection.dataFlow.DfaNullability

/**
 * Standalone replacement for IDEA's `DfType` — the base type of values tracked by the Java DFA.
 *
 * The DFA is not available in standalone mode (see
 * [com.intellij.codeInspection.dataFlow.CommonDataflow]); this class exists so the copied J2K
 * sources that inspect a data-flow type keep compiling.
 */
open class DfType

/**
 * Standalone replacement for IDEA's `DfReferenceType` — a DFA type describing a reference value and
 * its nullability.
 *
 * Instances are never produced in standalone mode; the API is kept for source compatibility.
 */
abstract class DfReferenceType : DfType() {

    /**
     * Returns the nullability of values of this type.
     *
     * @return the tracked nullability.
     */
    abstract fun getNullability(): DfaNullability
}
