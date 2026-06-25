/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.refactoring

import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.idea.base.fir.codeInsight.SymbolBasedShortenReferencesFacility

/**
 * Exposes the IDEA K2 `SymbolBasedShortenReferencesFacility` (declared `internal` in its
 * source file) to consumers outside the `KotlinRefactoring` module.
 *
 * The Nbm module registers an instance of this class as the application service implementation
 * for [ShortenReferencesFacility]; without it, IDEA's `InlinePostProcessor.shortenReferences`
 * throws `RuntimeException: Cannot find service` at the end of an inline refactoring.
 */
class KotlinSymbolBasedShortenReferencesFacility :
    ShortenReferencesFacility by SymbolBasedShortenReferencesFacility()
