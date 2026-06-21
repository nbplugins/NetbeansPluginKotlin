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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.navigation

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.netbeans.api.project.Project
import org.openide.filesystems.FileUtil
import java.io.File

/**
 * Computes type hierarchy relationships for a Kotlin class or interface using the K2 Analysis API.
 *
 * Two operations are supported:
 * - [computeSupertypes]: finds the immediate declared supertypes of a class/interface (upward).
 * - [computeSubtypes]: scans all project source files for classes that directly extend or
 *   implement the target (downward), using the same file-scan strategy as
 *   [KotlinGoToImplementationsAction].
 *
 * This class belongs to the **model/service** layer; it performs no UI operations.
 */
class KotlinTypeHierarchyComputer {

    /**
     * Returns the direct supertypes of [decl] declared in Kotlin source within the project.
     *
     * Uses `KaClassSymbol.superTypes` from the K2 Analysis API. Types that resolve to PSI
     * outside the current source set (e.g. JDK or library types) are omitted.
     *
     * @param decl The class or interface declaration to inspect.
     * @param session The active K2 analysis session for [decl]'s project.
     * @return Ordered list of supertypes reachable from [decl], filtered to Kotlin sources only.
     */
    fun computeSupertypes(
        decl: KtClassOrObject,
        session: KotlinAnalysisAPISession
    ): List<KotlinTypeHierarchyNode> {
        val fo = decl.containingFile.virtualFile?.let { FileUtil.toFileObject(File(it.path)) }
            ?: return emptyList()
        val kaKtFile = session.getKtFileForPath(fo.path) ?: return emptyList()

        return runCatching {
            analyze(kaKtFile) {
                val symbol = decl.symbol as? KaClassSymbol ?: return@analyze emptyList()
                symbol.superTypes.mapNotNull { superType ->
                    val superPsi = superType.symbol?.psi as? KtClassOrObject ?: return@mapNotNull null
                    val superVf = superPsi.containingFile.virtualFile ?: return@mapNotNull null
                    val superFo = FileUtil.toFileObject(File(superVf.path)) ?: return@mapNotNull null
                    KotlinTypeHierarchyNode.fromPsi(superPsi, superFo)
                }
            }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("KotlinTypeHierarchyComputer.computeSupertypes failed", e)
            emptyList()
        }
    }

    /**
     * Returns direct subtypes of [decl] found across all Kotlin source files in [project].
     *
     * Scans every `.kt` file in the project and checks whether its class declarations list
     * [decl] as a supertype. Each file is analyzed in its own `analyze {}` block so a failure
     * in one file does not abort the scan.
     *
     * @param decl The class or interface whose direct subtypes are requested.
     * @param project The NetBeans project that owns the source files to scan.
     * @param session The active K2 analysis session for [project].
     * @return List of classes/interfaces that directly extend or implement [decl].
     */
    fun computeSubtypes(
        decl: KtClassOrObject,
        project: Project,
        session: KotlinAnalysisAPISession
    ): List<KotlinTypeHierarchyNode> {
        val subtypes = mutableListOf<KotlinTypeHierarchyNode>()

        for (fo in KotlinPsiManager.getFilesByProject(project)) {
            val searchFile = session.getKtFileForPath(fo.path) ?: continue
            runCatching {
                analyze(searchFile) {
                    searchFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                            super.visitClassOrObject(classOrObject)
                            if (classOrObject == decl) return
                            val symbol = runCatching {
                                classOrObject.symbol as? KaClassSymbol
                            }.getOrNull() ?: return
                            val extendsTarget = runCatching {
                                symbol.superTypes.any { it.symbol?.psi == decl }
                            }.getOrNull() ?: return
                            if (extendsTarget) {
                                subtypes.add(KotlinTypeHierarchyNode.fromPsi(classOrObject, fo))
                            }
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinTypeHierarchyComputer.computeSubtypes failed for ${fo.path}", e
                )
            }
        }

        return subtypes
    }
}
