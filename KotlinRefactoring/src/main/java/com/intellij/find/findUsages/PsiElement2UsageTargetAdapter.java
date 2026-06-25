package com.intellij.find.findUsages;
import com.intellij.psi.PsiElement;
import com.intellij.usages.PsiElementUsageTarget;
import com.intellij.openapi.vfs.VirtualFile;
public class PsiElement2UsageTargetAdapter implements PsiElementUsageTarget {
    public PsiElement2UsageTargetAdapter(PsiElement element) {}
    @Override public PsiElement getElement() { return null; }
    @Override public void findUsages() {}
    @Override public boolean isValid() { return false; }
    @Override public boolean isReadOnly() { return false; }
    @Override public VirtualFile[] getFiles() { return null; }
    @Override public void update() {}
    @Override public String getPresentableText() { return ""; }
    @Override public String getLocationString() { return null; }
    @Override public javax.swing.Icon getIcon(boolean unused) { return null; }
    @Override public void navigate(boolean f) {}
    @Override public boolean canNavigate() { return false; }
    @Override public boolean canNavigateToSource() { return false; }
}
