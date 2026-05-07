package com.intellij.formatting;

public interface Formatter extends IndentFactory, WrapFactory, AlignmentFactory, SpacingFactory, FormattingModelFactory {
    static Formatter getInstance() {
        return Holder.INSTANCE;
    }
    FormattingModelBuilder createExternalFormattingModelBuilder(com.intellij.psi.PsiFile file, FormattingModelBuilder builder);
    boolean isEligibleForVirtualFormatting(com.intellij.psi.PsiElement element);
    FormattingModelBuilder wrapForVirtualFormatting(com.intellij.psi.PsiElement element, FormattingModelBuilder builder);

    final class Holder {
        static final Formatter INSTANCE = new FormatterImpl();
    }
}
