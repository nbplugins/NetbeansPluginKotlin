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
package io.github.nbplugins.kotlin.nbm.formatting;

import com.intellij.formatting.Block;
import com.intellij.formatting.FormatTextRanges;
import com.intellij.formatting.FormatterImpl;
import com.intellij.formatting.Indent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import java.lang.reflect.Proxy;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import com.intellij.psi.codeStyle.DocCommentSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleProvider;
import java.util.Collections;
import java.util.Set;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings;
import org.jetbrains.kotlin.idea.formatter.KotlinCommonCodeStyleSettings;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.kotlin.formatting.KotlinFormatter.KotlinSpacingBuilderUtilImpl;
import org.jetbrains.kotlin.formatting.KotlinFormatter;
import org.jetbrains.kotlin.formatting.NodeAlignmentStrategy;
import org.jetbrains.kotlin.formatting.KotlinBlock;
import org.jetbrains.kotlin.formatting.NetBeansDocumentFormattingModel;
import org.jetbrains.kotlin.formatting.NetBeansFormattingModel;
import org.jetbrains.kotlin.formatting.IndenterUtil;
import org.jetbrains.kotlin.idea.formatter.KotlinSpacingRulesKt;
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences;
import io.github.nbplugins.kotlin.nbm.model.KotlinEnvironment;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.utils.LineEndUtil;
import org.netbeans.api.project.Project;

/**
 *
 * @author Alexander.Baratynski
 */
public class KotlinFormatterUtils {

    /** Global singleton — loaded from IDE preferences on startup and on settings change. */
    private static final CodeStyleSettings settings;

    /**
     * Per-call override for per-project settings. Set via {@link #pushSettings} before
     * a format/indent call and cleared via {@link #popSettings} in a finally-block.
     * Using a thread-local avoids mutating the global singleton for every format operation
     * while remaining safe if formatting is ever parallelized.
     */
    private static final ThreadLocal<CodeStyleSettings> threadSettings = new ThreadLocal<>();

    static {
        settings = new CodeStyleSettings();
        registerKotlinProvider(settings);
    }

    /**
     * Registers the Kotlin {@link LanguageCodeStyleProvider} on the given {@link CodeStyleSettings}
     * so that {@link CodeStyleSettings#getCommonSettings(Language)} returns a
     * {@link KotlinCommonCodeStyleSettings} instance and {@link CodeStyleSettings#getCustomSettings}
     * returns a {@link KotlinCodeStyleSettings} instance.
     *
     * <p>Must be called on every freshly constructed {@code CodeStyleSettings} that will be used
     * for Kotlin formatting (the static singleton and any per-call preview instance).
     */
    public static void registerKotlinProvider(CodeStyleSettings s) {
        s.registerCommonSettings(new LanguageCodeStyleProvider() {
            @Override public Language getLanguage() { return KotlinLanguage.INSTANCE; }
            @Override public CommonCodeStyleSettings getDefaultCommonSettings() {
                KotlinCommonCodeStyleSettings c = new KotlinCommonCodeStyleSettings();
                c.initIndentOptions();
                return c;
            }
            @Override public DocCommentSettings getDocCommentSettings(CodeStyleSettings cs) { return DocCommentSettings.DEFAULTS; }
            @Override public Set<String> getSupportedFields() { return Collections.emptySet(); }
            @Override public CustomCodeStyleSettings createCustomSettings(CodeStyleSettings cs) { return new KotlinCodeStyleSettings(cs); }
        });
    }

    /**
     * Returns the effective {@link CodeStyleSettings} for the current formatting call.
     *
     * <p>Returns the thread-local override set by {@link #pushSettings} if present,
     * otherwise falls back to the global IDE settings singleton.
     *
     * @return effective code-style settings
     */
    public static CodeStyleSettings getSettings() {
        CodeStyleSettings override = threadSettings.get();
        return override != null ? override : settings;
    }

    /**
     * Sets a per-call settings override for the current thread. Must be paired with
     * {@link #popSettings} in a finally-block. Called by {@code formatUtils.kt} and
     * {@link org.jetbrains.kotlin.formatting.KotlinIndentStrategy} to activate
     * per-project settings without mutating the global singleton.
     *
     * @param s the settings to use for the current format/indent call
     */
    public static void pushSettings(CodeStyleSettings s) {
        threadSettings.set(s);
    }

    /**
     * Clears the per-call settings override for the current thread. Must be called in a
     * finally-block after {@link #pushSettings}.
     */
    public static void popSettings() {
        threadSettings.remove();
    }

    /**
     * Formats {@code source} using the supplied {@code customSettings} without touching
     * the global {@link #settings} singleton and without reloading from NbPreferences.
     *
     * <p>Used by the Tools&nbsp;&rarr;&nbsp;Options preview pane to render unsaved
     * spinner/checkbox state. The normal {@link #formatCode(String, String, Project, String)}
     * path overrides indent options from {@code IndenterUtil} and reloads the global
     * settings from {@link KotlinCodeStylePreferences#prefs()} inside {@code buildModel},
     * which would defeat any preview values; this method skips both steps.
     *
     * @param source         Kotlin source to format
     * @param fileName       virtual file name passed to the PSI factory
     * @param project        project providing the PSI factory
     * @param lineSeparator  line separator used when constructing the formatter
     * @param customSettings settings instance (must have the Kotlin provider registered;
     *                       see {@link #registerKotlinProvider(CodeStyleSettings)})
     */
    public static String formatCodeWithSettings(String source, String fileName, Project project,
            String lineSeparator, CodeStyleSettings customSettings) {
        KtPsiFactory psiFactory = createPsiFactory(project);
        KtFile ktFile = createKtFile(source, psiFactory, fileName);
        new FormatterImpl();
        Block rootBlock = new KotlinBlock(ktFile.getNode(),
                NodeAlignmentStrategy.getNullStrategy(),
                Indent.getNoneIndent(),
                null,
                customSettings,
                KotlinSpacingRulesKt.createSpacingBuilder(customSettings, KotlinSpacingBuilderUtilImpl.INSTANCE));
        NetBeansFormattingModel formattingDocumentModel =
                new NetBeansFormattingModel(
                        new DocumentImpl(ktFile.getViewProvider().getContents(), true),
                        ktFile, customSettings, false);
        NetBeansDocumentFormattingModel formattingModel = new NetBeansDocumentFormattingModel(
                ktFile, rootBlock, formattingDocumentModel, source, customSettings);
        FormatTextRanges ranges = new FormatTextRanges(ktFile.getTextRange(), true);
        new FormatterImpl().format(formattingModel, customSettings, customSettings.getIndentOptions(), ranges);
        return formattingModel.getNewText();
    }
    
    public static KtPsiFactory createPsiFactory(Project project) {
        KotlinEnvironment environment = KotlinEnvironment.Companion.getEnvironment(project);
        return new KtPsiFactory(environment.getProject());
    }
    
    public static String formatCode(String source, String fileName, Project project, String lineSeparator) {
        return formatCode(source, fileName, createPsiFactory(project), lineSeparator);
    }
    
    public static String formatCode(String source, String fileName, KtPsiFactory psiFactory, String lineSeparator) {
        return new KotlinFormatter(source, fileName, psiFactory, lineSeparator).formatCode();
    }
    
    public static String reformatAll(KtFile containingFile, Block rootBlock, 
            CodeStyleSettings settings, String source ) {
        return formatRange(containingFile, rootBlock, settings, source, containingFile.getTextRange());
    }
    
    public static String formatRange(String source, NetBeansDocumentRange range, 
            KtPsiFactory psiFactory, String fileName) {
        return formatRange(source, range.toPsiRange(source), psiFactory, fileName);
    }
    
    public static String formatRange(String source, TextRange range, KtPsiFactory psiFactory, String fileName) {
        KtFile ktFile = createKtFile(source, psiFactory, fileName);
        CodeStyleSettings s = getSettings();
        Block rootBlock = new KotlinBlock(ktFile.getNode(),
                NodeAlignmentStrategy.getNullStrategy(),
                Indent.getNoneIndent(),
                null,
                s,
                KotlinSpacingRulesKt.createSpacingBuilder(s, KotlinSpacingBuilderUtilImpl.INSTANCE));
        return formatRange(ktFile, rootBlock, s, source, range);
    }
    
    private static String formatRange(KtFile containingFile, Block rootBlock,
            CodeStyleSettings settings, String source, TextRange range) {
        NetBeansDocumentFormattingModel formattingModel = 
                buildModel(containingFile, rootBlock, settings, source, false);
        FormatTextRanges ranges = new FormatTextRanges(range, true);
        new FormatterImpl().format(formattingModel, settings, settings.getIndentOptions(), ranges);
        
        return formattingModel.getNewText();
    }
    
    private static void initializeSettings(IndentOptions options) {
        options.USE_TAB_CHARACTER = !IndenterUtil.isSpacesForTabs();
        options.INDENT_SIZE = IndenterUtil.getDefaultIndent();
        options.TAB_SIZE = IndenterUtil.getTabSize();
    }
    
    private static NetBeansDocumentFormattingModel buildModel(KtFile ktFile,
            Block rootBlock, CodeStyleSettings settings, String source,
            boolean forLineIndentation) {
        initializeSettings(settings.getIndentOptions());
        // Settings are loaded into the global singleton once on project open and on settings
        // change (Tools→Options OK / Project Properties OK). At format time the caller has
        // already pushed the correct settings via pushSettings(), so no per-call I/O is needed.
        NetBeansFormattingModel formattingDocumentModel =
                new NetBeansFormattingModel(
                        new DocumentImpl(ktFile.getViewProvider().getContents(), true),
                    ktFile, settings, forLineIndentation);
        
        return new NetBeansDocumentFormattingModel(
                ktFile, rootBlock, formattingDocumentModel, source, settings);
    }
    
    // ???
    public static Document getMockDocument(Document document) {
        return (Document) Proxy.newProxyInstance(
                document.getClass().getClassLoader(),
                new Class<?>[]{Document.class},
                (proxy, method, args) -> method.invoke(document, args));
    }
    
    public static KtFile createKtFile(String source, KtPsiFactory psiFactory, String fileName) {
        return psiFactory.createFile(fileName, StringUtil.convertLineSeparators(source));
    }
    
    private static TextRange getSignificantRange(KtFile file, int offset) {
        PsiElement elementAtOffset = file.findElementAt(offset);
        if (elementAtOffset == null) {
            int significantRangeStart = CharArrayUtil.shiftBackward(file.getText(), offset - 1, "\r\t ");
            return new TextRange(Math.max(significantRangeStart, 0), offset);
        }
        
        return elementAtOffset.getTextRange();
    }
    
    public static String adjustIndent(KtFile containingFile, Block rootBlock,
            CodeStyleSettings settings, int offset, String document) {
        NetBeansDocumentFormattingModel model = 
                buildModel(containingFile, rootBlock, settings, document, true);
        new FormatterImpl().adjustLineIndent(model, settings, settings.getIndentOptions(), 
                offset, getSignificantRange(containingFile, offset));
        
        return model.getNewText();
    }
    
    public static class NetBeansDocumentRange {
        private final int startOffset, endOffset;
        
        public NetBeansDocumentRange(int start, int end) {
            startOffset = start;
            endOffset = end;
        }
        
        public TextRange toPsiRange(String source) {
            int startPsiOffset = LineEndUtil.convertCrToDocumentOffset(source, startOffset);
            int endPsiOffset = LineEndUtil.convertCrToDocumentOffset(source, endOffset);
            
            return new TextRange(startPsiOffset, endPsiOffset);
        }
    }
}
