/**
 * *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 ******************************************************************************
 */
package org.jetbrains.kotlin.refactorings.rename;

import com.intellij.psi.PsiElement;
import io.github.nbplugins.kotlin.nbm.navigation.KotlinWhereUsedRefactoringUI;
import javax.swing.JEditorPane;
import javax.swing.text.StyledDocument;
import org.jetbrains.kotlin.builder.KotlinPsiManager;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.api.SafeDeleteRefactoring;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.spi.ui.ActionsImplementationProvider;
import org.netbeans.modules.refactoring.spi.ui.UI;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.windows.TopComponent;

/**
 * {@link ActionsImplementationProvider} for Kotlin source files.
 *
 * Enables the Rename (Alt+Shift+R), Find Usages (Alt+F7), and Safe Delete actions for
 * {@code .kt} files. Registered in {@code layer.xml} under {@code Services/} because
 * annotation processing is disabled by {@code -proc:none} and {@code @ServiceProvider} alone
 * does not generate {@code META-INF/services} entries in this mixed Kotlin+Java build.
 */
@org.openide.util.lookup.ServiceProvider(service = ActionsImplementationProvider.class, position = 400)
public class KotlinActionsImplementationProvider extends ActionsImplementationProvider {

    @Override
    public boolean canRename(Lookup lookup) {
        EditorCookie ec = lookup.lookup(EditorCookie.class);
        if (ec == null) {
            return false;
        }
        StyledDocument doc = ec.getDocument();
        if (doc == null) {
            return false;
        }
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);
        return fo != null && fo.hasExt("kt");
    }

    @Override
    public void doRename(Lookup lookup) {
        EditorCookie ec = lookup.lookup(EditorCookie.class);
        JEditorPane pane = ec.getOpenedPanes()[0];
        // Use selection start so that renaming a fully-selected word positions inside the word,
        // not past it. When no selection, getSelectionStart() == getCaretPosition().
        int caretPosition = pane.getSelectionStart();
        StyledDocument doc = ec.getDocument();
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);
        final KtFile ktFile = KotlinPsiManager.INSTANCE.getParsedFile(fo);
        final PsiElement psi = ktFile.findElementAt(caretPosition);
        // Do NOT include fo (FileObject) in the lookup — a built-in NetBeans rename plugin
        // would see it and rename the file on disk instead of renaming the code symbol.
        UI.openRefactoringUI(
                new KotlinRenameRefactoringUI(psi, new RenameRefactoring(Lookups.fixed(psi, doc, caretPosition))),
                TopComponent.getRegistry().getActivated());
    }

    /**
     * Returns {@code true} when the active editor contains a {@code .kt} file.
     *
     * @param lookup the lookup provided by NetBeans for the current action context
     * @return {@code true} if Find Usages can be invoked on the current selection
     */
    @Override
    public boolean canFindUsages(Lookup lookup) {
        EditorCookie ec = lookup.lookup(EditorCookie.class);
        if (ec == null) {
            return false;
        }
        StyledDocument doc = ec.getDocument();
        if (doc == null) {
            return false;
        }
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);
        return fo != null && fo.hasExt("kt");
    }

    /**
     * Returns {@code true} when the active editor contains a {@code .kt} file,
     * enabling the Safe Delete action for Kotlin symbols.
     *
     * @param lookup the lookup provided by NetBeans for the current action context
     * @return {@code true} if Safe Delete can be invoked on the current selection
     */
    @Override
    public boolean canDelete(Lookup lookup) {
        EditorCookie ec = lookup.lookup(EditorCookie.class);
        if (ec == null) {
            return false;
        }
        StyledDocument doc = ec.getDocument();
        if (doc == null) {
            return false;
        }
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);
        return fo != null && fo.hasExt("kt");
    }

    /**
     * Opens the Safe Delete dialog for the Kotlin symbol at the current caret position.
     *
     * Resolves the named declaration under the cursor, creates a {@link SafeDeleteRefactoring}
     * carrying the caret offset and document, and opens {@link KotlinSafeDeleteUI}.
     *
     * @param lookup the lookup provided by NetBeans for the current action context
     */
    @Override
    public void doDelete(Lookup lookup) {
        EditorCookie ec = lookup.lookup(EditorCookie.class);
        JEditorPane pane = ec.getOpenedPanes()[0];
        int caretPosition = pane.getSelectionStart();
        StyledDocument doc = ec.getDocument();
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);

        // Resolve the symbol name for the dialog title.
        String symbolName = "";
        KtFile ktFile = KotlinPsiManager.INSTANCE.getParsedFile(fo);
        if (ktFile != null) {
            com.intellij.psi.PsiElement element = ktFile.findElementAt(caretPosition);
            com.intellij.psi.PsiElement decl =
                com.intellij.psi.util.PsiTreeUtil.getNonStrictParentOfType(
                    element, org.jetbrains.kotlin.psi.KtNamedDeclaration.class);
            if (decl instanceof org.jetbrains.kotlin.psi.KtNamedDeclaration) {
                String name = ((org.jetbrains.kotlin.psi.KtNamedDeclaration) decl).getName();
                if (name != null) symbolName = name;
            }
        }

        SafeDeleteRefactoring refactoring =
            new SafeDeleteRefactoring(Lookups.fixed(caretPosition, doc));
        UI.openRefactoringUI(
            new KotlinSafeDeleteUI(symbolName, refactoring),
            TopComponent.getRegistry().getActivated());
    }

    /**
     * Opens the Find Usages dialog for the Kotlin symbol at the current caret position.
     *
     * Creates a {@link WhereUsedQuery} carrying the caret offset, the open document, and the
     * source {@link FileObject}, then opens the refactoring UI which triggers
     * {@link KotlinWhereUsedPlugin#prepare} to search for references across the project.
     *
     * @param lookup the lookup provided by NetBeans for the current action context
     */
    @Override
    public void doFindUsages(Lookup lookup) {
        EditorCookie ec = lookup.lookup(EditorCookie.class);
        JEditorPane pane = ec.getOpenedPanes()[0];
        int caretPosition = pane.getCaretPosition();
        StyledDocument doc = ec.getDocument();
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);
        WhereUsedQuery query = new WhereUsedQuery(Lookups.fixed(caretPosition, doc, fo));
        UI.openRefactoringUI(new KotlinWhereUsedRefactoringUI(query, fo, caretPosition),
                TopComponent.getRegistry().getActivated());
    }
}
