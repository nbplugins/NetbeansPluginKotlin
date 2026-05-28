/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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

package io.github.nbplugins.kotlin.nbm.filesystem

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject

/**
 * K2 Analysis API implementation of Java stub generation for Kotlin source files.
 *
 * This object forms the **K2 primary path** for virtual source (light class) generation.
 * Instead of the K1 pipeline (KtFile → bytecode via [org.jetbrains.kotlin.codegen.KotlinCodegenFacade]
 * → ASM decompile → Java source), this generator produces Java stub text directly from K2 symbols,
 * bypassing bytecode entirely.
 *
 * Each returned pair is `(className, javaStubSource)` where `className` is the simple or
 * relative class name (e.g. `"MyClass"` or `"Outer.Inner"`) and `javaStubSource` is a minimal
 * but syntactically valid Java compilation unit that declares the class with all its public
 * methods and properties. Method bodies are empty `{}`.
 *
 * [getJavaStubs] returns `null` when no K2 session is available so that
 * [org.jetbrains.kotlin.filesystem.virtualSourceUtils] can fall back to the K1 bytecode path.
 *
 * This object belongs to the **service/model** layer and must not reference NetBeans UI APIs.
 */
object KaLightClassGenerator {

    /**
     * Generates Java stub source code from K2 symbols for the given Kotlin [file].
     *
     * Returns a list of `(className, javaStubSource)` pairs, one per class declared in the file
     * plus an optional facade class for top-level functions/properties. Returns `null` when the
     * K2 session is unavailable or analysis fails, signalling that the caller should fall back to
     * the K1 path.
     *
     * @param file the Kotlin source file
     * @param project the owning NetBeans project
     * @return list of (className, Java source) pairs, or null to signal K1 fallback
     */
    fun getJavaStubs(file: FileObject, project: Project): List<Pair<String, String>>? {
        val session = KotlinAnalysisAPISession.getSession(project) ?: return null
        val kaKtFile = session.getKtFileForPath(file.path) ?: return null
        return runCatching {
            analyze(kaKtFile) { buildStubs(kaKtFile) }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("K2 light class generation failed for ${file.path}", e)
            null
        }
    }

    /**
     * Collects all stub pairs for the given [kaKtFile] inside an active [KaSession].
     *
     * For each [KtClassOrObject] a class stub is generated. Top-level [KtNamedFunction] and
     * [KtProperty] declarations are gathered and emitted as a single facade class whose name is
     * derived from [JvmFileClassUtil.getFileClassInternalName].
     */
    private fun KaSession.buildStubs(kaKtFile: KtFile): List<Pair<String, String>> {
        val packageName = kaKtFile.packageFqName.asString()
        val stubs = mutableListOf<Pair<String, String>>()
        val hasTopLevelCallables = kaKtFile.declarations.any { it is KtNamedFunction || it is KtProperty }

        for (decl in kaKtFile.declarations) {
            if (decl is KtClassOrObject) {
                val sym = decl.classSymbol ?: continue
                val className = sym.classId?.relativeClassName?.asString()?.replace(".", "$") ?: continue
                stubs.add(className to buildClassStub(sym, packageName, decl))
            }
        }

        if (hasTopLevelCallables) {
            val facadeName = JvmFileClassUtil.getFileClassInternalName(kaKtFile).substringAfterLast("/")
            stubs.add(facadeName to buildFacadeStub(kaKtFile, packageName, facadeName))
        }

        return stubs
    }

    /**
     * Generates a minimal Java class/interface/enum stub for [sym].
     *
     * The stub includes:
     * - package declaration
     * - class declaration with the appropriate keyword (`class`, `interface`, `enum`, `@interface`)
     * - PSI-sourced supertype list (avoids symbol resolution for supertypes)
     * - one stub method per declared callable in [sym]'s [KaClassSymbol.declaredMemberScope]
     *
     * @param sym the class symbol to generate a stub for
     * @param packageName the package name, used for the `package` declaration
     * @param psiDecl the original PSI node, used for PSI-sourced supertype text
     */
    context(KaSession)
    private fun buildClassStub(sym: KaClassSymbol, packageName: String, psiDecl: KtClassOrObject): String {
        val sb = StringBuilder()
        if (packageName.isNotEmpty()) sb.appendLine("package $packageName;")

        val shortName = sym.classId?.shortClassName?.asString() ?: return ""
        val keyword = when (sym.classKind) {
            KaClassKind.INTERFACE -> "interface"
            KaClassKind.ENUM_CLASS -> "enum"
            KaClassKind.ANNOTATION_CLASS -> "@interface"
            else -> "class"
        }

        sb.append("public $keyword $shortName")

        // Use PSI supertype text directly — avoids resolving type symbols for supertypes.
        val superEntries = psiDecl.superTypeListEntries
        if (superEntries.isNotEmpty()) {
            sb.append(" extends ${superEntries.joinToString(", ") { it.text }}")
        }
        sb.appendLine(" {")

        for (callable in sym.declaredMemberScope.callables { true }) {
            when (callable) {
                is KaNamedFunctionSymbol -> sb.appendLine(buildMethodStub(callable, shortName))
                is KaPropertySymbol -> sb.appendLine(buildFieldStub(callable))
                else -> {}
            }
        }

        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * Generates a Java method stub for [sym].
     *
     * The return type and parameter types are rendered via [KaTypeRendererForSource] and then
     * mapped to their Java equivalents for primitive Kotlin types. Constructor stubs (when the
     * method name matches the [className]) omit the return type.
     */
    context(KaSession)
    private fun buildMethodStub(sym: KaNamedFunctionSymbol, className: String): String {
        val name = sym.name.asString()
        val isConstructor = name == className
        val returnType = if (isConstructor) "" else {
            sym.returnType
                .render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                .toJavaType() + " "
        }
        val params = sym.valueParameters.mapIndexed { i, p ->
            "${p.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT).toJavaType()} a$i"
        }.joinToString(", ")
        return "    public $returnType$name($params) {}"
    }

    /**
     * Generates a Java field stub for [sym].
     *
     * The field type is rendered via [KaTypeRendererForSource] and mapped to its Java equivalent.
     */
    context(KaSession)
    private fun buildFieldStub(sym: KaPropertySymbol): String {
        val name = sym.name.asString()
        val type = sym.returnType
            .render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            .toJavaType()
        return "    public $type $name;"
    }

    /**
     * Generates a minimal Java facade class for top-level Kotlin functions and properties.
     *
     * The facade is declared as a `public final class` matching the JVM facade name computed
     * by [JvmFileClassUtil]. Individual top-level declarations are not individually stubbed here
     * because they would need full K2 symbol resolution; the empty facade class is sufficient
     * to prevent NetBeans from complaining about missing class files.
     */
    private fun buildFacadeStub(kaKtFile: KtFile, packageName: String, facadeName: String): String {
        val sb = StringBuilder()
        if (packageName.isNotEmpty()) sb.appendLine("package $packageName;")
        sb.appendLine("public final class $facadeName {}")
        return sb.toString()
    }

    /**
     * Maps a Kotlin short type name to its Java primitive equivalent.
     *
     * Non-primitive Kotlin types (e.g. `String`, generic types, nullable types) are returned
     * with the nullable marker stripped. This mapping is best-effort and handles the common
     * cases for code-completion stubs; precise JVM descriptor mapping is handled by the K1
     * fallback path.
     */
    private fun String.toJavaType(): String = when (removeSuffix("?")) {
        "Int" -> "int"
        "Long" -> "long"
        "Double" -> "double"
        "Float" -> "float"
        "Boolean" -> "boolean"
        "Byte" -> "byte"
        "Short" -> "short"
        "Char" -> "char"
        "Unit" -> "void"
        "Any" -> "Object"
        else -> removeSuffix("?")
    }
}
