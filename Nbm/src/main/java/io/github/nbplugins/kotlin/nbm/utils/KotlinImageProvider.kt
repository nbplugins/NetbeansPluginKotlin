/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.utils

import javax.swing.ImageIcon
import org.openide.util.ImageUtilities

/**
 * Provides icons for Kotlin declarations used in completion and the Navigator structure view.
 *
 * Kotlin-specific icons (val, var, extension function, class kinds) come from
 * netbeans-plugin-kotlin-icons (Apache 2.0, converted from IntelliJ IDEA SVGs at build time).
 * The plain function icon (method.png) is a Java-style fallback since IDEA uses a platform icon
 * for non-extension functions.
 */
object KotlinImageProvider {

    private fun load(path: String): ImageIcon? =
        ImageUtilities.loadImage(path)?.let { ImageIcon(it) }

    val functionImage: ImageIcon?          = load("io/github/nbplugins/kotlin/nbm/icons/function.png")
    val methodImage: ImageIcon?            = load("io/github/nbplugins/kotlin/nbm/icons/method.png")

    // Kotlin-specific icons from netbeans-plugin-kotlin-icons (io/github/nbplugins/kotlin/nbm/icons/)
    val extensionFunctionImage: ImageIcon? = load("io/github/nbplugins/kotlin/nbm/icons/extension_function.png")
    val suspendFunctionImage: ImageIcon?   = load("io/github/nbplugins/kotlin/nbm/icons/suspend_function.png")
    val valImage: ImageIcon?               = load("io/github/nbplugins/kotlin/nbm/icons/val.png")
    val varImage: ImageIcon?               = load("io/github/nbplugins/kotlin/nbm/icons/var.png")
    val classKotlinImage: ImageIcon?       = load("io/github/nbplugins/kotlin/nbm/icons/class_kotlin.png")
    val abstractClassImage: ImageIcon?     = load("io/github/nbplugins/kotlin/nbm/icons/abstract_class.png")
    val interfaceImage: ImageIcon?         = load("io/github/nbplugins/kotlin/nbm/icons/interface.png")
    val enumImage: ImageIcon?              = load("io/github/nbplugins/kotlin/nbm/icons/enum.png")
    val annotationImage: ImageIcon?        = load("io/github/nbplugins/kotlin/nbm/icons/annotation.png")
    val objectImage: ImageIcon?            = load("io/github/nbplugins/kotlin/nbm/icons/object.png")
    val typeAliasImage: ImageIcon?         = load("io/github/nbplugins/kotlin/nbm/icons/type_alias.png")
    val lambdaImage: ImageIcon?            = load("io/github/nbplugins/kotlin/nbm/icons/lambda.png")
    // Platform icons (platform/icons/src/nodes/)
    val variableImage: ImageIcon?          = load("io/github/nbplugins/kotlin/nbm/icons/variable.png")
    val parameterImage: ImageIcon?         = load("io/github/nbplugins/kotlin/nbm/icons/parameter.png")
}
