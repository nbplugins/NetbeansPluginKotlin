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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Detects references that inserting an explicit import would silently retarget.
 *
 * An explicit import takes precedence over wildcard imports, other explicit imports are a
 * conflicting import, and imports also win over declarations of the importing file's own package.
 * A refactoring that adds or rewrites an import must therefore prove that every unqualified
 * reference the imported name could capture already points at the declaration it is meant for;
 * otherwise the refactoring would change which declaration is called while still compiling.
 */
object KotlinImportBindingCheck {

    /**
     * Tells whether importing [name] into [file] would capture a reference to another declaration.
     *
     * Only unqualified references are considered: a selector keeps resolving through its receiver
     * whatever the file imports. Locals, parameters, and members keep binding to themselves as
     * well, so only symbols that an import can actually shadow count.
     *
     * @param file caller that is about to import a declaration named [name]
     * @param name simple name the import binds
     * @param excludedDeclarations declarations the import is meant to retarget; references that
     * resolve to one of them, or to a constructor of a class among them, are not conflicts
     * @return `true` when some unqualified reference in [file] would change its target
     */
    fun hasConflictingReference(
        file: KtFile,
        name: String,
        excludedDeclarations: Set<PsiElement>,
    ): Boolean = runCatching {
        analyze(file) {
            var conflicting = false
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    super.visitSimpleNameExpression(expression)
                    if (conflicting) return
                    if (expression.getReferencedName() != name) return
                    if (expression.isQualifiedSelector()) return
                    if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                    val symbol = expression.references.filterIsInstance<KtReference>().firstOrNull()
                        ?.resolveToSymbol() ?: return
                    if (symbol.belongsTo(excludedDeclarations)) return
                    if (!symbol.isTopLevelBinding()) return
                    conflicting = true
                }
            })
            conflicting
        }
    }.getOrDefault(false)

    /**
     * Tells whether this symbol is one of the declarations whose references the import retargets.
     *
     * A constructor call resolves to a constructor whose PSI belongs to the constructed class, so
     * the enclosing classifier is compared as well.
     *
     * @param excludedDeclarations PSI of the declarations the import is meant for
     * @return `true` when this symbol is one of them or a constructor of one
     */
    private fun KaSymbol.belongsTo(excludedDeclarations: Set<PsiElement>): Boolean {
        val element = psi ?: return false
        if (element in excludedDeclarations) return true
        val enclosingClass = element.enclosingClassOrObject()
        return enclosingClass != null && enclosingClass in excludedDeclarations
    }

    /**
     * Tells whether an explicit import could change how this symbol is referenced.
     *
     * Only top-level declarations compete with an import: locals, parameters, and members are
     * resolved lexically or through their receiver and keep binding to themselves.
     *
     * @return `true` for a top-level class, constructor, callable, or property
     */
    private fun KaSymbol.isTopLevelBinding(): Boolean = when (this) {
        is KaConstructorSymbol -> containingClassId?.let { classId -> !classId.isLocal } == true
        is KaCallableSymbol -> callableId?.let { callableId -> callableId.className == null } == true
        is KaClassLikeSymbol -> classId?.let { classId -> !classId.isLocal } == true
        is KaVariableSymbol -> psi is KtProperty && psi?.parent is KtFile
        else -> false
    }

    /**
     * Returns the innermost class or object containing this element, or `null` at file level.
     *
     * @return the enclosing classifier, or `null` when this element is a top-level declaration
     */
    private fun PsiElement.enclosingClassOrObject(): KtClassOrObject? {
        var current: PsiElement? = this
        while (current != null && current !is KtFile) {
            if (current is KtClassOrObject) return current
            current = current.parent
        }
        return null
    }

    /**
     * Tells whether this name is the selector of a qualified access.
     *
     * Only an unqualified name is rebound by an import; `receiver.name` keeps resolving through
     * its receiver whatever the file imports.
     *
     * @return `true` when this expression follows a dot or a type qualifier
     */
    private fun KtSimpleNameExpression.isQualifiedSelector(): Boolean {
        (parent as? KtCallExpression)
            ?.takeIf { call -> call.calleeExpression == this }
            ?.let { call ->
                (call.parent as? KtDotQualifiedExpression)?.let { qualified ->
                    return qualified.selectorExpression == call
                }
            }
        (parent as? KtDotQualifiedExpression)?.let { qualified ->
            return qualified.selectorExpression == this
        }
        return (parent as? KtUserType)?.let { userType ->
            userType.referenceExpression == this && userType.qualifier != null
        } == true
    }
}
