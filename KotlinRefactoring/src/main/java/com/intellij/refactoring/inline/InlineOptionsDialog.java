package com.intellij.refactoring.inline;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiElement;
public abstract class InlineOptionsDialog extends DialogWrapper {
    protected final PsiElement myElement;
    protected final boolean myInvokedOnReference;
    protected InlineOptionsDialog(Project project, boolean canBeParent, PsiElement element) {
        super(project, canBeParent);
        this.myElement = element;
        this.myInvokedOnReference = false;
    }
    protected abstract boolean isInlineThis();
    public abstract boolean isKeepTheDeclaration();
    protected abstract String getNameLabelText();
    protected abstract String getBorderTitle();
    protected abstract String getInlineAllText();
    protected abstract String getInlineThisText();
    protected abstract boolean isInlineThis(boolean inlineThis);
    @Override protected javax.swing.JComponent createCenterPanel() { return null; }
}
