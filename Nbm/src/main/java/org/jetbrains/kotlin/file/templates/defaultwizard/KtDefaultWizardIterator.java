/** *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 ****************************************************************************** */
package org.jetbrains.kotlin.file.templates.defaultwizard;

import com.google.common.collect.Lists;
import java.awt.Component;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.event.ChangeListener;
import org.jetbrains.kotlin.file.templates.packagechooser.PackageChooser;
import org.jetbrains.kotlin.file.templates.packagechooser.TargetChooserPanel;
import org.jetbrains.kotlin.project.KotlinSourceGroup;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ui.templates.support.Templates;
import org.openide.WizardDescriptor;
import org.openide.filesystems.FileLock;
import org.openide.filesystems.FileObject;
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession;
import org.openide.loaders.DataFolder;
import org.openide.loaders.DataObject;
import org.openide.util.HelpCtx;

public abstract class KtDefaultWizardIterator implements WizardDescriptor.InstantiatingIterator<WizardDescriptor> {

    private final String type;

    public KtDefaultWizardIterator(String type) {
        this.type = type;
    }

    private int index;

    private WizardDescriptor wizard;
    private TargetChooserPanel packageChooserPanel;
    private List<WizardDescriptor.Panel<WizardDescriptor>> panels;

    /**
     * Returns source groups for Kotlin source roots ({@code src/main/kotlin},
     * {@code src/test/kotlin}) of the given project. The Maven/Gradle NetBeans
     * integration does not expose these as {@code SOURCES_TYPE_JAVA} groups, so
     * the wizard would otherwise miss them and could not detect the package
     * when invoked from a {@code src/main/kotlin/...} folder.
     */
    private static List<SourceGroup> kotlinSourceGroups(Project project) {
        List<SourceGroup> result = new ArrayList<>();
        FileObject projectDir = project.getProjectDirectory();
        if (projectDir == null) {
            return result;
        }
        for (String relPath : new String[]{"src/main/kotlin", "src/test/kotlin"}) {
            FileObject root = projectDir.getFileObject(relPath);
            if (root != null && root.isFolder()) {
                result.add(new KotlinSourceGroup(root));
            }
        }
        return result;
    }

    private List<WizardDescriptor.Panel<WizardDescriptor>> getPanels() {
        if (panels == null) {
            Project project = Templates.getProject(wizard);
            SourceGroup[] groups;
            
            if (project == null) {
                List<SourceGroup> sourceGroups = Lists.newArrayList();

                for (Project proj : OpenProjects.getDefault().getOpenProjects()) {
                    Sources sources = ProjectUtils.getSources(proj);
                    if (sources != null) {
                        sourceGroups.addAll(Arrays.asList(
                                sources.getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)));
                        sourceGroups.addAll(kotlinSourceGroups(proj));
                        project = proj;
                        break;
                    }

                }
                groups = sourceGroups.toArray(new SourceGroup[sourceGroups.size()]);
            } else {
                Sources sources = ProjectUtils.getSources(project);
                List<SourceGroup> sourceGroups = new ArrayList<>(Arrays.asList(
                        sources.getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)));
                sourceGroups.addAll(kotlinSourceGroups(project));
                groups = sourceGroups.toArray(new SourceGroup[0]);
            }
            packageChooserPanel = PackageChooser.createPackageChooser(project, groups, new KtWizardPanel(), type + " name");
            
            panels = new ArrayList<>();
            panels.add(packageChooserPanel);
            String[] steps = createSteps();
            for (int i = 0; i < panels.size(); i++) {
                Component c = panels.get(i).getComponent();
                if (steps[i] == null) {
                    // Default step name to component name of panel. Mainly
                    // useful for getting the name of the target chooser to
                    // appear in the list of steps.
                    steps[i] = c.getName();
                }
                if (c instanceof JComponent) { // assume Swing components
                    JComponent jc = (JComponent) c;
                    jc.putClientProperty(WizardDescriptor.PROP_CONTENT_SELECTED_INDEX, i);
                    jc.putClientProperty(WizardDescriptor.PROP_CONTENT_DATA, steps);
                    jc.putClientProperty(WizardDescriptor.PROP_AUTO_WIZARD_STYLE, true);
                    jc.putClientProperty(WizardDescriptor.PROP_CONTENT_DISPLAYED, true);
                    jc.putClientProperty(WizardDescriptor.PROP_CONTENT_NUMBERED, true);
                }
            }
        }

        return panels;
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(KtDefaultWizardIterator.class.getName());

    @Override
    public Set<?> instantiate() throws IOException {
        String packageName = PackageChooser.pack;
        FileObject template = Templates.getTemplate(wizard);
        FileObject dir = Templates.getTargetFolder(wizard);
        String targetName = Templates.getTargetName(wizard);

        LOG.log(java.util.logging.Level.INFO,
                "KtDefaultWizardIterator.instantiate: template={0} targetName={1} package={2} dir={3}",
                new Object[]{
                        template.getPath(),
                        targetName,
                        packageName,
                        dir == null ? "null" : dir.getPath()
                });

        FileObject createdFile;
        try {
            String templateBody = template.asText("UTF-8");
            String rendered = renderTemplate(templateBody, packageName, targetName);
            String ext = template.getExt();
            createdFile = dir.createData(targetName, ext);
            FileLock lock = createdFile.lock();
            try (OutputStream out = createdFile.getOutputStream(lock);
                 Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write(rendered);
            } finally {
                lock.releaseLock();
            }

            // The K2 Analysis API session is built once per project and freezes the
            // list of source files at construction time. Any file created later is
            // invisible to KaSession.getKtFileForPath(), so the Navigator structure
            // scanner returns an empty list for it. Invalidate the session so it is
            // rebuilt on the next access, picking up the freshly-created file.
            Project project = Templates.getProject(wizard);
            if (project != null) {
                KotlinAnalysisAPISession.Companion.invalidate(project);
            }
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.SEVERE,
                    "KtDefaultWizardIterator.instantiate: manual render failed", t);
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new IOException(t);
        }

        LOG.log(java.util.logging.Level.INFO,
                "KtDefaultWizardIterator.instantiate: created={0} size={1}",
                new Object[]{createdFile.getPath(), createdFile.getSize()});

        DataObject createdDo = DataObject.find(createdFile);
        return Collections.singleton(createdDo);
    }

    /**
     * Manual template renderer — bypasses NetBeans' FreeMarker pipeline which
     * silently produces empty files when the {@code freemarker} script engine
     * is not visible to our module's classloader.
     *
     * <p>Supported syntax (all that current Kotlin templates use):
     * <ul>
     *   <li>{@code <#if package?? && package != ""> ... </#if>} — kept when
     *       {@code pkg} is non-empty, otherwise stripped entirely (along with
     *       its trailing newline);
     *   <li>{@code ${package}} — replaced by {@code pkg};
     *   <li>{@code ${name}} — replaced by {@code name}.
     * </ul>
     */
    static String renderTemplate(String body, String pkg, String name) {
        String src = body;
        int ifStart = src.indexOf("<#if package?? && package != \"\">");
        if (ifStart >= 0) {
            int blockStart = ifStart + "<#if package?? && package != \"\">".length();
            // Skip the newline immediately after the opening tag, if any.
            if (blockStart < src.length() && src.charAt(blockStart) == '\n') {
                blockStart++;
            }
            int closeStart = src.indexOf("</#if>", blockStart);
            int blockEnd = closeStart;
            int closeEnd = closeStart + "</#if>".length();
            // Swallow the newline after the closing tag too, so blank lines
            // don't accumulate when the block is dropped.
            if (closeEnd < src.length() && src.charAt(closeEnd) == '\n') {
                closeEnd++;
            }
            String inner = src.substring(blockStart, blockEnd);
            String replacement = (pkg != null && !pkg.isEmpty()) ? inner : "";
            src = src.substring(0, ifStart) + replacement + src.substring(closeEnd);
        }
        if (pkg == null) pkg = "";
        src = src.replace("${package}", pkg);
        src = src.replace("${name}", name == null ? "" : name);
        return src;
    }

    @Override
    public void initialize(WizardDescriptor wizard) {
        this.wizard = wizard;
    }

    @Override
    public void uninitialize(WizardDescriptor wizard) {
        panels = null;
    }

    @Override
    public WizardDescriptor.Panel<WizardDescriptor> current() {
        return getPanels().get(index);
    }

    @Override
    public String name() {
        return index + 1 + ". from " + getPanels().size();
    }

    @Override
    public boolean hasNext() {
        return index < getPanels().size() - 1;
    }

    @Override
    public boolean hasPrevious() {
        return index > 0;
    }

    @Override
    public void nextPanel() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        index++;
    }

    @Override
    public void previousPanel() {
        if (!hasPrevious()) {
            throw new NoSuchElementException();
        }
        index--;
    }

    // If nothing unusual changes in the middle of the wizard, simply:
    @Override
    public void addChangeListener(ChangeListener l) {
    }

    @Override
    public void removeChangeListener(ChangeListener l) {
    }
    // If something changes dynamically (besides moving between panels), e.g.
    // the number of panels changes in response to user input, then use
    // ChangeSupport to implement add/removeChangeListener and call fireChange
    // when needed

    // You could safely ignore this method. Is is here to keep steps which were
    // there before this wizard was instantiated. It should be better handled
    // by NetBeans Wizard API itself rather than needed to be implemented by a
    // client code.
    private String[] createSteps() {
        String[] beforeSteps = (String[]) wizard.getProperty("WizardPanel_contentData");
        assert beforeSteps != null : "This wizard may only be used embedded in the template wizard";
        String[] res = new String[(beforeSteps.length - 1) + panels.size()];
        for (int i = 0; i < res.length; i++) {
            if (i < (beforeSteps.length - 1)) {
                res[i] = beforeSteps[i];
            } else {
                res[i] = panels.get(i - beforeSteps.length + 1).getComponent().getName();
            }
        }
        return res;
    }

    private static class KtWizardPanel implements WizardDescriptor.Panel<WizardDescriptor> {

        private KtVisualPanel1 component;

        @Override
        public KtVisualPanel1 getComponent() {
            if (component == null) {
                component = new KtVisualPanel1();
            }
            return component;
        }

        @Override
        public HelpCtx getHelp() {
            return HelpCtx.DEFAULT_HELP;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void addChangeListener(ChangeListener l) {
        }

        @Override
        public void removeChangeListener(ChangeListener l) {
        }

        @Override
        public void readSettings(WizardDescriptor wiz) {
        }

        @Override
        public void storeSettings(WizardDescriptor wiz) {
        }
    }

}
