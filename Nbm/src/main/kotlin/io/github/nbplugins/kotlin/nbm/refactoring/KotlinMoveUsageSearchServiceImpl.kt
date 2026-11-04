/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.refactoring

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.compat.getClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.components.packageScope
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findPackage
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Real (project-wide) implementation of [KotlinMoveUsageSearchService], registered as an
 * application service by [KotlinAnalysisAPISession.registerStandaloneServices].
 *
 * IDEA's move engine expects `ReferencesSearch.search()`, backed by a project index this plugin's
 * standalone container never builds (`NoOpPsiSearchHelper` makes it a no-op). This reuses the same
 * whole-project scan-and-resolve strategy already used by Find Usages (E7)'s
 * [io.github.nbplugins.kotlin.nbm.navigation.KaFindUsagesComputer]: iterate every `KtFile` the
 * session that owns [declaration] knows about and resolve each reference expression.
 */
class KotlinMoveUsageSearchServiceImpl : KotlinMoveUsageSearchService {
    override fun findUsages(declaration: KtNamedDeclaration): List<PsiReference> {
        val session = KotlinAnalysisAPISession.forProject(declaration.project) ?: return emptyList()
        val references = mutableListOf<PsiReference>()
        for (kaFile in session.fileMap.values) {
            val ktFile = session.getKtFileForPath(kaFile.path) ?: continue
            runCatching {
                analyze(ktFile) {
                    // A constructor call resolves to a constructor symbol whose PSI is the class's
                    // primary constructor rather than the class declaration, so compare the
                    // constructed class id with the moved class in addition to PSI identity.
                    val targetClassId = (declaration as? KtClassOrObject)?.getClassOrObjectSymbol()?.classId
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            super.visitSimpleNameExpression(expression)
                            // Skip references inside import directives: K2ReferenceMutateService's
                            // bindToElement() on an import-path segment can leave the KtImportDirective
                            // invalidated mid-replace (adding/removing import statements restructures
                            // the whole import list) standalone, without live-document sync to keep it
                            // consistent. Not needed anyway — retargeting the *code* usages (call
                            // sites, etc.) to a fully-qualified reference and then shortening already
                            // adds/removes the right import via Kotlin's own shorten-references pass.
                            if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                            // Visit simple-name expressions specifically (not the generic
                            // KtReferenceExpression, which for a call site's KtCallExpression node
                            // has no *simple-name* reference K2MoveRenameUsageInfo.Source.retarget
                            // can bindToElement() on — only its callee child, a
                            // KtNameReferenceExpression, does).
                            val reference = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
                            val symbol = runCatching { reference.resolveToSymbol() }.getOrNull() ?: return
                            val constructsTarget = targetClassId != null &&
                                (symbol as? KaConstructorSymbol)?.containingClassId == targetClassId
                            if (symbol.psi == declaration || constructsTarget) {
                                references += reference
                            }
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinMoveUsageSearchServiceImpl: search failed in ${ktFile.name}", e
                )
            }
        }
        return references
    }

    /**
     * Enumerates every Kotlin source file of the standalone session.
     *
     * @param project IntelliJ project owning the standalone K2 session
     * @return session source files, or an empty list when no session exists
     */
    override fun findSourceFiles(project: com.intellij.openapi.project.Project): List<KtFile> {
        val session = KotlinAnalysisAPISession.forProject(project) ?: return emptyList()
        return session.fileMap.values.mapNotNull { lightFile -> session.getKtFileForPath(lightFile.path) }
    }

    /**
     * Finds Kotlin files that directly import a moved declaration or one nested inside it.
     *
     * Import directives are intentionally absent from [findUsages] because K2 semantic rebinding
     * cannot safely replace an import-path segment in standalone PSI. The caller replaces whole
     * directives after semantic retargeting instead.
     *
     * A file importing `old.Outer.Nested` names the moved class `old.Outer` only as a path prefix,
     * so no `Nested` usage resolves to the moved declaration and usage search cannot discover the
     * file. Such imports are matched here by their moved-declaration prefix. Aliased directives
     * are included as well: their path still follows the moved declaration while the alias stays.
     *
     * @param project IntelliJ project owning the standalone K2 session
     * @param movedImports original package/name keys mapped to final packages
     * @return session files with a direct import that needs rewriting
     */
    override fun findImportingFiles(
        project: com.intellij.openapi.project.Project,
        movedImports: Map<MoveImport, FqName>,
    ): List<KtFile> {
        if (movedImports.isEmpty()) return emptyList()
        val session = KotlinAnalysisAPISession.forProject(project) ?: return emptyList()
        val candidates = session.fileMap.values.mapNotNull { lightFile ->
            val file = session.getKtFileForPath(lightFile.path) ?: return@mapNotNull null
            file.takeIf { candidate ->
                candidate.importDirectives.any { directive ->
                    !directive.isAllUnder && directive.importedFqName?.let { imported ->
                        importsMovedDeclaration(imported, movedImports)
                    } == true
                }
            }
        }
        if (candidates.isNotEmpty()) {
            KotlinLogger.INSTANCE.logInfo(
                "KotlinMoveUsageSearchServiceImpl: found ${candidates.size} import-only Move File usage(s)",
            )
        }
        return candidates
    }

    /**
     * Tells whether [imported] names a moved declaration, directly or as a nested prefix.
     *
     * @param imported fully qualified path of the import directive
     * @param movedImports original package/name keys mapped to final packages
     * @return `true` when [imported] or one of its prefixes is a moved declaration
     */
    private fun importsMovedDeclaration(imported: FqName, movedImports: Map<MoveImport, FqName>): Boolean {
        fun isMoved(fqName: FqName): Boolean =
            MoveImport(fqName.parent(), fqName.shortName().asString()) in movedImports

        if (isMoved(imported)) return true
        var prefix = imported.parent()
        while (!prefix.isRoot) {
            if (isMoved(prefix)) return true
            prefix = prefix.parent()
        }
        return false
    }

    /**
     * Finds Kotlin files with a wildcard import rooted in a moved class.
     *
     * Such a caller may reference only nested declarations of that class, so K2 usage discovery
     * never reports it; the import prefix is the only place the moved class is named. A wildcard
     * rooted in a class nested inside a moved class (`old.Outer.Inner.*`) names the moved class as
     * a path prefix, so prefixes are matched as well.
     *
     * @param project IntelliJ project owning the standalone K2 session
     * @param classFqNames old fully qualified names of moved classes
     * @return session files whose class-scoped wildcard import follows a moved class
     */
    override fun findWildcardImportingFiles(
        project: com.intellij.openapi.project.Project,
        classFqNames: Set<FqName>,
    ): List<KtFile> {
        if (classFqNames.isEmpty()) return emptyList()
        val session = KotlinAnalysisAPISession.forProject(project) ?: return emptyList()
        return session.fileMap.values.mapNotNull { lightFile ->
            val file = session.getKtFileForPath(lightFile.path) ?: return@mapNotNull null
            file.takeIf { candidate ->
                candidate.importDirectives.any { directive ->
                    directive.isAllUnder && directive.importedFqName?.let { imported ->
                        classFqNames.any { movedClass -> movedClass.isPrefixOf(imported) }
                    } == true
                }
            }
        }
    }

    /**
     * Finds Kotlin files whose package wildcard imports one of [packageFqNames].
     *
     * The whole session is scanned because such a caller references no moved declaration; the
     * import directive itself is the only evidence that it depends on the old package.
     *
     * @param project IntelliJ project owning the standalone K2 session
     * @param packageFqNames packages named by wildcard import directives
     * @return session files with a package-level wildcard import of one of those packages
     */
    override fun findPackageWildcardImportingFiles(
        project: com.intellij.openapi.project.Project,
        packageFqNames: Set<FqName>,
    ): List<KtFile> {
        if (packageFqNames.isEmpty()) return emptyList()
        val session = KotlinAnalysisAPISession.forProject(project) ?: return emptyList()
        return session.fileMap.values.mapNotNull { lightFile ->
            val file = session.getKtFileForPath(lightFile.path) ?: return@mapNotNull null
            file.takeIf { candidate ->
                candidate.importDirectives.any { directive ->
                    directive.isAllUnder && directive.importedFqName in packageFqNames
                }
            }
        }
    }

    /**
     * Tells whether [packageFqName] keeps any declaration outside [excludedPaths].
     *
     * Source files are checked first because they are cheap to enumerate. A dependency JAR can
     * declare a package of the same name, in which case a wildcard import of that package keeps
     * resolving even after the last source file leaves it.
     *
     * @param project IntelliJ project owning the standalone K2 session
     * @param packageFqName package whose remaining declarations are checked
     * @param excludedPaths paths of files whose package is changing in this operation
     * @return `true` when a source file or a dependency declaration still belongs to the package
     */
    override fun hasDeclarationsInPackage(
        project: com.intellij.openapi.project.Project,
        packageFqName: FqName,
        excludedPaths: Set<String>,
    ): Boolean {
        val session = KotlinAnalysisAPISession.forProject(project) ?: return true
        val sourceRemains = session.fileMap.values.any { lightFile ->
            lightFile.path !in excludedPaths &&
                session.getKtFileForPath(lightFile.path)?.packageFqName == packageFqName
        }
        if (sourceRemains) return true
        val probe = session.fileMap.values
            .firstNotNullOfOrNull { lightFile -> session.getKtFileForPath(lightFile.path) }
            ?: return true
        return runCatching {
            analyze(probe) {
                val packageSymbol = findPackage(packageFqName) ?: return@analyze false
                // Dependency declarations carry light PSI rather than none, so a declaration is
                // attributed to the moving files by its source path: what remains belongs either
                // to another source file or to a dependency JAR.
                packageSymbol.packageScope.declarations.any { declaration ->
                    val path = declaration.psi?.containingFile?.virtualFile?.path
                    path == null || path !in excludedPaths
                }
            }
        }.getOrDefault(true)
    }

    /**
     * Tells whether this FQN equals [fqName] or is one of its package/class prefixes.
     *
     * @param fqName fully qualified name to test
     * @return `true` when this FQN names the same declaration or one of its enclosing segments
     */
    private fun FqName.isPrefixOf(fqName: FqName): Boolean {
        val prefix = pathSegments()
        val segments = fqName.pathSegments()
        return segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix
    }
}
