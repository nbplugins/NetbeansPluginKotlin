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
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinFilesystemRenameUI;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinMoveSourceSelection;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinProjectFolderRenameSelection;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinRenameDirectoryRefactoring;
import java.awt.EventQueue;
import java.util.Collection;
import javax.swing.JEditorPane;
import javax.swing.text.StyledDocument;
import org.jetbrains.kotlin.builder.KotlinPsiManager;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.api.SafeDeleteRefactoring;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.api.ui.ExplorerContext;
import org.netbeans.modules.refactoring.spi.ui.ActionsImplementationProvider;
import org.netbeans.modules.refactoring.spi.ui.UI;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
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
@org.openide.util.lookup.ServiceProvider(service = ActionsImplementationProvider.class, position = 99)
public class KotlinActionsImplementationProvider extends ActionsImplementationProvider {

    /**
     * Returns whether the context is an eligible Kotlin package folder, physical Kotlin file, or open Kotlin editor.
     *
     * @param lookup lookup supplied by the Refactor action
     * @return {@code true} when this provider owns the Rename operation
     */
    @Override
    public boolean canRename(Lookup lookup) {
        if (KotlinProjectFolderRenameSelection.INSTANCE.resolve(lookup) != null) {
            return true;
        }
        if (hasNodeSelection(lookup)) {
            return selectedKotlinFile(lookup) != null;
        }
        return canRenameEditor(lookup);
    }

    /**
     * Opens physical file/directory rename for a project-tree selection or symbol rename for an editor selection.
     *
     * @param lookup lookup supplied by the Refactor action
     */
    @Override
    public void doRename(Lookup lookup) {
        KotlinMoveSourceSelection folderSelection = KotlinProjectFolderRenameSelection.INSTANCE.resolve(lookup);
        if (folderSelection != null) {
            openDirectoryRename(lookup, folderSelection);
            return;
        }

        if (hasNodeSelection(lookup)) {
            FileObject file = selectedKotlinFile(lookup);
            if (file != null) {
                openFileRename(file);
            }
            return;
        }

        EditorCookie ec = lookup.lookup(EditorCookie.class);
        if (ec != null && ec.getOpenedPanes() != null && ec.getOpenedPanes().length > 0) {
            JEditorPane pane = ec.getOpenedPanes()[0];
            // Use selection start so that renaming a fully-selected word positions inside the word,
            // not past it. When no selection, getSelectionStart() == getCaretPosition().
            int caretPosition = pane.getSelectionStart();
            StyledDocument doc = ec.getDocument();
            FileObject fo = doc == null ? null : ProjectUtils.getFileObjectForDocument(doc);
            final KtFile ktFile = fo == null ? null : KotlinPsiManager.INSTANCE.getParsedFile(fo);
            final PsiElement psi = ktFile == null ? null : ktFile.findElementAt(caretPosition);
            if (psi != null) {
                // Do NOT include fo (FileObject) in the lookup — a built-in NetBeans rename plugin
                // would see it and rename the file on disk instead of renaming the code symbol.
                UI.openRefactoringUI(
                        new KotlinRenameRefactoringUI(psi, new RenameRefactoring(Lookups.fixed(psi, doc, caretPosition))),
                        TopComponent.getRegistry().getActivated());
                return;
            }
        }

    }

    /** Opens the explicit filesystem rename UI for one Kotlin file selected in a project tree. */
    private void openFileRename(FileObject file) {
        EventQueue.invokeLater(() -> UI.openRefactoringUI(
                new KotlinFilesystemRenameUI(
                        new io.github.nbplugins.kotlin.nbm.refactoring.KotlinRenameFileRefactoring(file, file.getName()),
                        false,
                        file.getNameExt()),
                TopComponent.getRegistry().getActivated()));
    }

    /**
     * Resolves one physical Kotlin source file from a file-node lookup without requiring an editor.
     *
     * @param lookup lookup supplied by NetBeans
     * @return selected valid Kotlin file, or {@code null} for folders and unrelated selections
     */
    static FileObject selectedKotlinFile(Lookup lookup) {
        Collection<? extends Node> nodes = lookup.lookupAll(Node.class);
        if (nodes.size() != 1) {
            return null;
        }
        Lookup nodeLookup = nodes.iterator().next().getLookup();
        FileObject file = nodeLookup.lookup(FileObject.class);
        if (file == null) {
            DataObject dataObject = nodeLookup.lookup(DataObject.class);
            file = dataObject == null ? null : dataObject.getPrimaryFile();
        }
        return file != null && file.isValid() && !file.isFolder() && file.hasExt("kt") ? file : null;
    }

    /**
     * Returns whether the lookup originated from an Explorer node selection.
     *
     * An Explorer selection must not fall back to the active editor because Refactor was invoked
     * for the selected node, even when that file happens to be open.
     *
     * @param lookup lookup supplied by NetBeans
     * @return {@code true} when one or more nodes are selected
     */
    static boolean hasNodeSelection(Lookup lookup) {
        return !lookup.lookupAll(Node.class).isEmpty();
    }

    /**
     * Tests the pre-existing declaration-rename editor context without involving folder selection.
     *
     * @param lookup lookup supplied by NetBeans
     * @return whether the lookup identifies an open Kotlin document
     */
    private boolean canRenameEditor(Lookup lookup) {
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
     * Opens the existing physical directory-rename lifecycle on the event-dispatch thread.
     *
     * @param lookup lookup that may contain an inline Explorer rename request
     * @param selection eligible Kotlin package directory and its Kotlin descendants
     */
    private void openDirectoryRename(Lookup lookup, KotlinMoveSourceSelection selection) {
        FileObject directory = selection.getSelectedDirectory();
        if (directory == null) {
            return;
        }
        ExplorerContext explorerContext = lookup.lookup(ExplorerContext.class);
        String requestedName = explorerContext == null ? null : explorerContext.getNewName();
        String initialName = KotlinProjectFolderRenameSelection.INSTANCE.initialName(directory, requestedName);
        EventQueue.invokeLater(() -> UI.openRefactoringUI(
                new KotlinFilesystemRenameUI(
                        new KotlinRenameDirectoryRefactoring(directory, initialName, selection),
                        true,
                        directory.getName()),
                TopComponent.getRegistry().getActivated()));
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
