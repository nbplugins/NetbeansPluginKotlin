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
package io.github.nbplugins.kotlin.nbm.navigation

import org.netbeans.modules.csl.api.DeclarationFinder
import org.netbeans.modules.csl.api.HtmlFormatter

/**
 * A single alternative navigation target for Kotlin "Go to Super" / "Go to Implementation"
 * results.
 *
 * Wraps a [KotlinElementHandle] and a [DeclarationFinder.DeclarationLocation] so that CSL
 * can show a chooser popup when there are multiple overriding or overridden declarations.
 *
 * @param handle the element handle for the target declaration
 * @param location the file-and-offset location to navigate to
 */
class KotlinAlternativeLocation(
    private val handle: KotlinElementHandle,
    private val location: DeclarationFinder.DeclarationLocation
) : DeclarationFinder.AlternativeLocation {

    override fun getElement(): KotlinElementHandle = handle

    override fun getDisplayHtml(formatter: HtmlFormatter): String {
        formatter.appendText(handle.name)
        val container = handle.`in`
        if (container.isNotEmpty()) {
            formatter.appendText(" in $container")
        }
        return formatter.text
    }

    override fun getLocation(): DeclarationFinder.DeclarationLocation = location

    override fun compareTo(other: DeclarationFinder.AlternativeLocation): Int =
        handle.name.compareTo(other.element?.name ?: "")
}
