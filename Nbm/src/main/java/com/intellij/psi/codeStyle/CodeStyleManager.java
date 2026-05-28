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
package com.intellij.psi.codeStyle;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ThrowableRunnable;
import java.util.Collection;

/**
 * Standalone no-op replacement for the abstract {@code com.intellij.psi.codeStyle.CodeStyleManager}
 * from {@code core:253}.
 *
 * <p>The real implementation requires IntelliJ code-style infrastructure that is absent in the
 * standalone (NetBeans) environment.  This replacement is loaded first by the classloader (via
 * {@code Nbm/src/main/java/}) so that {@code KtPsiFactory} methods using {@code createByPattern}
 * / {@code createExpressionByPattern} can call
 * {@code CodeStyleManager.getInstance(project).reformat(...)} without an NPE.
 *
 * <p>{@code reformat} methods return the element unchanged; all other formatting operations are
 * no-ops.  The static {@link #getInstance(Project)} method returns the singleton no-op instance.
 */
public class CodeStyleManager {

    private static final CodeStyleManager INSTANCE = new CodeStyleManager(null);

    private final Project project;

    public CodeStyleManager(Project project) {
        this.project = project;
    }

    /**
     * Returns a no-op singleton.  Called by {@code CreateByPatternKt.createByPattern}.
     *
     * @param project ignored in standalone mode
     * @return singleton no-op instance
     */
    public static CodeStyleManager getInstance(Project project) {
        return INSTANCE;
    }

    public static CodeStyleManager getInstance(com.intellij.psi.PsiManager psiManager) {
        return INSTANCE;
    }

    public Project getProject() {
        return project;
    }

    public PsiElement reformat(PsiElement element) throws IncorrectOperationException {
        return element;
    }

    public PsiElement reformat(PsiElement element, boolean canChangeWhiteSpacesOnly) throws IncorrectOperationException {
        return element;
    }

    public PsiElement reformatRange(PsiElement element, int startOffset, int endOffset) throws IncorrectOperationException {
        return element;
    }

    public PsiElement reformatRange(PsiElement element, int startOffset, int endOffset, boolean canChangeWhiteSpacesOnly) throws IncorrectOperationException {
        return element;
    }

    public void reformatText(PsiFile file, int startOffset, int endOffset) throws IncorrectOperationException {
    }

    public void reformatText(PsiFile file, Collection<? extends TextRange> ranges) throws IncorrectOperationException {
    }

    public void reformatTextWithContext(PsiFile file, Collection<? extends TextRange> ranges) throws IncorrectOperationException {
    }

    public void adjustLineIndent(PsiFile file, TextRange rangeToAdjust) throws IncorrectOperationException {
    }

    public int adjustLineIndent(PsiFile file, int offset) throws IncorrectOperationException {
        return offset;
    }

    public int adjustLineIndent(Document document, int offset) {
        return offset;
    }

    public boolean isLineToBeIndented(PsiFile file, int offset) {
        return false;
    }

    public String getLineIndent(PsiFile file, int offset) {
        return "";
    }

    public String getLineIndent(Document document, int offset) {
        return "";
    }

    public Indent getIndent(String text, FileType fileType) {
        return null;
    }

    public String fillIndent(Indent indent, FileType fileType) {
        return "";
    }

    public Indent zeroIndent() {
        return null;
    }

    public void reformatNewlyAddedElement(ASTNode block, ASTNode addedElement) throws IncorrectOperationException {
    }

    public boolean isSequentialProcessingAllowed() {
        return false;
    }

    public void performActionWithFormatterDisabled(Runnable r) {
        r.run();
    }

    public <T extends Throwable> void performActionWithFormatterDisabled(ThrowableRunnable<T> r) throws T {
        r.run();
    }

    public <T> T performActionWithFormatterDisabled(Computable<T> r) {
        return r.compute();
    }
}
