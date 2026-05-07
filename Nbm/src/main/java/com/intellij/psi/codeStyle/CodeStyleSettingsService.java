package com.intellij.psi.codeStyle;

import com.intellij.openapi.Disposable;
import java.util.Collections;
import java.util.List;

public interface CodeStyleSettingsService {
    static CodeStyleSettingsService getInstance() {
        return new CodeStyleSettingsService() {
            @Override public void addListener(CodeStyleSettingsServiceListener l, Disposable d) {}
            @Override public List<? extends FileTypeIndentOptionsFactory> getFileTypeIndentOptionsFactories() { return Collections.emptyList(); }
            @Override public List<? extends CustomCodeStyleSettingsFactory> getCustomCodeStyleSettingsFactories() { return Collections.emptyList(); }
            @Override public List<? extends LanguageCodeStyleProvider> getLanguageCodeStyleProviders() { return Collections.emptyList(); }
        };
    }
    void addListener(CodeStyleSettingsServiceListener listener, Disposable parentDisposable);
    List<? extends FileTypeIndentOptionsFactory> getFileTypeIndentOptionsFactories();
    List<? extends CustomCodeStyleSettingsFactory> getCustomCodeStyleSettingsFactories();
    List<? extends LanguageCodeStyleProvider> getLanguageCodeStyleProviders();
}
