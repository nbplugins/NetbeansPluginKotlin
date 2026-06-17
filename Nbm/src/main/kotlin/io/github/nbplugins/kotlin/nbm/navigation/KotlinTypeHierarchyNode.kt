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

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.openide.filesystems.FileObject

/**
 * Classifies the syntactic kind of a Kotlin type hierarchy node, matching IDEA's visual
 * distinction between classes, interfaces, objects, enums, and annotations.
 */
enum class HierarchyKind {
    /** A concrete or abstract class (`class`, `abstract class`). */
    CLASS,
    /** A Kotlin interface (`interface`). */
    INTERFACE,
    /** A Kotlin object declaration or companion object. */
    OBJECT,
    /** An enum class (`enum class`). */
    ENUM,
    /** An annotation class (`annotation class`). */
    ANNOTATION
}

/**
 * Represents one node in a Kotlin type hierarchy tree.
 *
 * @property name Simple class/interface name shown in the UI tree.
 * @property fqName Fully-qualified name, or null for anonymous/local types.
 * @property kind Syntactic kind of this type (class, interface, object, enum, annotation).
 * @property psi The underlying PSI element; null for types from outside the project (e.g. JDK).
 * @property fileObject The source file that declares this type; null when [psi] is null.
 * @property children Direct subtypes or supertypes (populated lazily by [KotlinTypeHierarchyComputer]).
 */
data class KotlinTypeHierarchyNode(
    val name: String,
    val fqName: String?,
    val kind: HierarchyKind,
    val psi: KtClassOrObject?,
    val fileObject: FileObject?,
    val children: MutableList<KotlinTypeHierarchyNode> = mutableListOf()
) {
    companion object {
        /**
         * Creates a [KotlinTypeHierarchyNode] from a [KtClassOrObject] PSI element.
         *
         * @param psi The PSI class/interface/object declaration.
         * @param fileObject The [FileObject] that contains [psi].
         * @return A node with name, fqName, and kind derived from the PSI without `analyze {}`.
         */
        fun fromPsi(psi: KtClassOrObject, fileObject: FileObject): KotlinTypeHierarchyNode {
            val name = psi.name ?: "<anonymous>"
            val fqName = psi.fqName?.asString()
            val kind = when {
                psi is KtClass && psi.isAnnotation() -> HierarchyKind.ANNOTATION
                psi is KtClass && psi.isEnum() -> HierarchyKind.ENUM
                psi is KtClass && psi.isInterface() -> HierarchyKind.INTERFACE
                psi is KtObjectDeclaration -> HierarchyKind.OBJECT
                else -> HierarchyKind.CLASS
            }
            return KotlinTypeHierarchyNode(name, fqName, kind, psi, fileObject)
        }
    }
}
