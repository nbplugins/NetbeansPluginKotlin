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
 * Enables the Rename (Alt+Shift+R) and Find Usages (Alt+F7) actions for {@code .kt} files.
 * Registered in {@code layer.xml} under {@code Services/} because annotation processing is
 * disabled by {@code -proc:none} and {@code @ServiceProvider} alone does not generate
 * {@code META-INF/services} entries in this mixed Kotlin+Java build.
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
        int caretPosition = pane.getCaretPosition();
        StyledDocument doc = ec.getDocument();
        FileObject fo = ProjectUtils.getFileObjectForDocument(doc);
        final KtFile ktFile = KotlinPsiManager.INSTANCE.getParsedFile(fo);
        final PsiElement psi = ktFile.findElementAt(caretPosition);
        UI.openRefactoringUI(new KotlinRenameRefactoringUI(psi, new RenameRefactoring(Lookups.fixed(psi, doc))),
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
