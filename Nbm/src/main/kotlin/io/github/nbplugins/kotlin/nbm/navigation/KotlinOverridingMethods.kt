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

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.utils.LineEndUtil
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import org.netbeans.modules.csl.api.DeclarationFinder
import org.netbeans.modules.csl.api.ElementHandle
import org.netbeans.modules.csl.api.OverridingMethods
import org.netbeans.modules.csl.spi.ParserResult
import org.jetbrains.kotlin.navigation.getFileObjectFromJar
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import java.io.File

/**
 * [OverridingMethods] implementation for Kotlin, enabling gutter annotations (▲ / ▼) in the
 * editor margin for methods that override or are overridden by other Kotlin declarations.
 *
 * CSL calls this from `ComputeAnnotations` for each [StructureItem] whose
 * [ElementHandle] is non-null. The handle must be a [KotlinElementHandle]; calls with
 * any other handle type return `null` / `false`.
 *
 * **"Go to Super"** (▲ / Ctrl+Shift+P): [overrides] returns all symbols that [handle]'s
 * declaration directly overrides, using [KaCallableSymbol.allOverriddenSymbols].
 *
 * **"Go to Implementations"** (▼ / Ctrl+Alt+B): [overriddenBy] scans all project Kotlin
 * source files and collects declarations whose [allOverriddenSymbols] include the target.
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 */
class KotlinOverridingMethods : OverridingMethods {

    /**
     * Returns all declarations that [handle]'s declaration directly overrides (▲ gutter icon).
     *
     * @param info parser result for the current file
     * @param handle the element handle for the declaration to inspect
     * @return list of [DeclarationFinder.AlternativeLocation]s for the overridden declarations,
     *         or `null` if the symbol has no overridden declarations or [handle] is not a
     *         [KotlinElementHandle]
     */
    override fun overrides(
        info: ParserResult,
        handle: ElementHandle
    ): Collection<DeclarationFinder.AlternativeLocation>? {
        val kotlinHandle = handle as? KotlinElementHandle ?: return null
        val kr = info as? KotlinParserResult ?: return null
        return runCatching {
            val fo = kr.file
            val project = kr.project
            val session = KotlinAnalysisAPISession.getSession(project)
            val kaKtFile = session.getKtFileForPath(fo.path) ?: return null

            val element = kaKtFile.findElementAt(kotlinHandle.startOffset) ?: return null
            val decl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java) ?: return null

            val results = mutableListOf<DeclarationFinder.AlternativeLocation>()
            analyze(kaKtFile) {
                val symbol = decl.symbol as? KaCallableSymbol ?: return null
                for (overridden in symbol.allOverriddenSymbols) {
                    val psi = overridden.psi as? KtNamedDeclaration ?: continue
                    val targetFo = fileObjectForPsi(psi, fo) ?: continue
                    val offset = LineEndUtil.convertCrToDocumentOffset(
                        psi.containingFile.text, psi.textOffset
                    )
                    val targetHandle = KotlinElementHandle.fromPsi(psi, targetFo)
                    val location = DeclarationFinder.DeclarationLocation(targetFo, offset, targetHandle)
                    results.add(KotlinAlternativeLocation(targetHandle, location))
                }
            }
            results.takeIf { it.isNotEmpty() }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("KotlinOverridingMethods.overrides failed", e)
            null
        }
    }

    /**
     * Returns `true` if [handle]'s declaration can be overridden — i.e. it has `open`,
     * `abstract`, or `interface`-member modality — enabling the ▼ gutter annotation.
     *
     * @param info parser result for the current file
     * @param handle the element handle to check
     * @return `true` if the declaration is overridable, `false` otherwise
     */
    override fun isOverriddenBySupported(
        info: ParserResult,
        handle: ElementHandle
    ): Boolean {
        val kotlinHandle = handle as? KotlinElementHandle ?: return false
        val kr = info as? KotlinParserResult ?: return false
        return runCatching {
            val fo = kr.file
            val project = kr.project
            val session = KotlinAnalysisAPISession.getSession(project)
            val kaKtFile = session.getKtFileForPath(fo.path) ?: return false

            val element = kaKtFile.findElementAt(kotlinHandle.startOffset) ?: return false
            val decl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java) ?: return false

            analyze(kaKtFile) {
                val symbol = decl.symbol as? KaCallableSymbol ?: return false
                symbol.modality in setOf(KaSymbolModality.ABSTRACT, KaSymbolModality.OPEN)
            }
        }.getOrElse { false }
    }

    /**
     * Returns all declarations that override [handle]'s declaration (▼ gutter icon /
     * "Go to Implementations").
     *
     * Scans all Kotlin source files in the project and collects declarations whose
     * [KaCallableSymbol.allOverriddenSymbols] include the target declaration's PSI element.
     *
     * @param info parser result for the current file
     * @param handle the element handle for the base declaration
     * @return list of [DeclarationFinder.AlternativeLocation]s for overriding declarations,
     *         or `null` if none are found or [handle] is not a [KotlinElementHandle]
     */
    override fun overriddenBy(
        info: ParserResult,
        handle: ElementHandle
    ): Collection<DeclarationFinder.AlternativeLocation>? {
        val kotlinHandle = handle as? KotlinElementHandle ?: return null
        val kr = info as? KotlinParserResult ?: return null
        return runCatching {
            val fo = kr.file
            val project = kr.project
            val session = KotlinAnalysisAPISession.getSession(project)
            val kaKtFile = session.getKtFileForPath(fo.path) ?: return null

            val element = kaKtFile.findElementAt(kotlinHandle.startOffset) ?: return null
            val targetDecl = PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java) ?: return null

            val allFiles = KotlinPsiManager.getFilesByProject(project)
            val results = mutableListOf<DeclarationFinder.AlternativeLocation>()

            for (searchFo in allFiles) {
                val searchFile = session.getKtFileForPath(searchFo.path) ?: continue
                runCatching {
                    analyze(searchFile) {
                        searchFile.accept(object : KtTreeVisitorVoid() {
                            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                                super.visitNamedDeclaration(declaration)
                                val symbol = runCatching { declaration.symbol as? KaCallableSymbol }.getOrNull()
                                    ?: return
                                val overrides = runCatching {
                                    symbol.allOverriddenSymbols.any { it.psi == targetDecl }
                                }.getOrNull() ?: return
                                if (overrides) {
                                    val targetFo = fileObjectForPsi(declaration, searchFo) ?: return
                                    val offset = LineEndUtil.convertCrToDocumentOffset(
                                        declaration.containingFile.text, declaration.textOffset
                                    )
                                    val targetHandle = KotlinElementHandle.fromPsi(declaration, targetFo)
                                    val location = DeclarationFinder.DeclarationLocation(targetFo, offset, targetHandle)
                                    results.add(KotlinAlternativeLocation(targetHandle, location))
                                }
                            }
                        })
                    }
                }.onFailure { e ->
                    KotlinLogger.INSTANCE.logException(
                        "KotlinOverridingMethods.overriddenBy: scan failed in ${searchFo.path}", e
                    )
                }
            }
            results.takeIf { it.isNotEmpty() }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("KotlinOverridingMethods.overriddenBy failed", e)
            null
        }
    }

    /**
     * Resolves the [FileObject] for a PSI element's containing file, relative to a known
     * [currentFo] in the same project.
     *
     * Returns `null` when the virtual file path cannot be mapped to a [FileObject] (e.g. for
     * binary-only class files or when the file is not on disk).
     */
    private fun fileObjectForPsi(psi: PsiElement, currentFo: FileObject): FileObject? {
        val virtualFile = psi.containingFile.virtualFile ?: return null
        return FileUtil.toFileObject(File(virtualFile.path))
            ?: getFileObjectFromJar(virtualFile.path)
    }
}
