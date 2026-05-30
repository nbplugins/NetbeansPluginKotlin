/*******************************************************************************
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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.projectsextensions.maven;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.ImageIcon;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.log.KotlinLogger;
import org.jetbrains.kotlin.projectsextensions.maven.buildextender.PomXmlModifier;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.openide.awt.NotificationDisplayer;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.ImageUtilities;

/**
 * Helper for accessing Maven project information.
 *
 * Note: classes from {@code org.apache.maven.*} (MavenProject, Artifact, Dependency)
 * and {@code org.netbeans.modules.maven.api.NbMavenProject} are accessed via reflection
 * because the maven-embedder/maven NetBeans modules use {@code OpenIDE-Module-Friends}
 * to restrict public-package access to a fixed list of friend modules. Direct imports
 * would cause NoClassDefFoundError at runtime.
 */
public class MavenHelper {

    private static final Map<Project, List<? extends Project>> depProjects = new HashMap<>();

    private static final List<Project> askedToConfigure = new ArrayList<>();

    public static void configure(Project project) {
        if (askedToConfigure.contains(project)) {
            return;
        }

        Object mavenProject = getOriginalMavenProject(project);
        if (mavenProject == null) return;
        if (!kotlinPluginConfigured(mavenProject)) {
            final PomXmlModifier pomModifier = new PomXmlModifier(project);
            ActionListener listener = new ActionListener(){
                @Override
                public void actionPerformed(ActionEvent e) {
                    pomModifier.checkPom();
                }
            };
            NotificationDisplayer.getDefault().notify("Kotlin is not configured",
                    new ImageIcon(ImageUtilities.loadImage("org/jetbrains/kotlin/kotlin.png")),
                    "Configure Kotlin", listener);
        }

        askedToConfigure.add(project);
    }

    private static boolean kotlinPluginConfigured(Object mavenProject) {
        try {
            Object pluginArtifacts = mavenProject.getClass().getMethod("getPluginArtifacts").invoke(mavenProject);
            if (!(pluginArtifacts instanceof Collection)) return false;

            for (Object plugin : (Collection<?>) pluginArtifacts) {
                String groupId = (String) plugin.getClass().getMethod("getGroupId").invoke(plugin);
                String artifactId = (String) plugin.getClass().getMethod("getArtifactId").invoke(plugin);

                if ("org.jetbrains.kotlin".equals(groupId)
                        && "kotlin-maven-plugin".equals(artifactId)) return true;
            }
        } catch (ReflectiveOperationException ex) {
            KotlinLogger.INSTANCE.logException("kotlinPluginConfigured failed", ex);
        }

        return false;
    }

    public static boolean hasParent(Project project) {
        return getParentProjectDirectory(project.getProjectDirectory()) != null;
    }

    /** Returns an {@code org.apache.maven.project.MavenProject} instance, or null. */
    public static Object getOriginalMavenProject(Project proj) {
        Class<?> clazz = proj.getClass();
        try {
            Method getOriginalProject = clazz.getMethod("getOriginalMavenProject");
            return getOriginalProject.invoke(proj);
        } catch (ReflectiveOperationException ex) {
            Exceptions.printStackTrace(ex);
        }

        return null;
    }

    /** Returns an {@code org.netbeans.modules.maven.api.NbMavenProject} instance, or null. */
    public static Object getProjectWatcher(Project proj) {
        Class<?> clazz = proj.getClass();
        try {
            Method getProjectWatcher = clazz.getMethod("getProjectWatcher");
            return getProjectWatcher.invoke(proj);
        } catch (ReflectiveOperationException ex) {
            Exceptions.printStackTrace(ex);
        }

        return null;
    }

    @Nullable
    public static Project getMavenProject(FileObject dir) throws IOException {
        if (dir == null) {
            return null;
        }

        if (ProjectManager.getDefault().isProject(dir)){
            return ProjectManager.getDefault().findProject(dir);
        }

        return null;
    }

    public static boolean isModuled(Project project) {
        Object originalProject = getOriginalMavenProject(project);
        if (originalProject == null) {
            return false;
        }
        try {
            Object modules = originalProject.getClass().getMethod("getModules").invoke(originalProject);
            return (modules instanceof Collection) && !((Collection<?>) modules).isEmpty();
        } catch (ReflectiveOperationException ex) {
            KotlinLogger.INSTANCE.logException("isModuled failed", ex);
            return false;
        }
    }

    public static boolean isMavenMainModuledProject(Project project) {
        if (isModuled(project)) {
            try {
                if (getMavenProject(project.getProjectDirectory().getParent()) == null) {
                    return true;
                }
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        return false;
    }

    private static FileObject getParentProjectDirectory(FileObject proj) {
        if (ProjectManager.getDefault().isProject(proj.getParent())) {
            FileObject parent = getParentProjectDirectory(proj.getParent());

            if (parent != null) {
                return parent;
            }
            return proj.getParent();
        }

        return null;
    }

    private static FileObject getMainParentFolder(Project proj) {
        FileObject projDir = proj.getProjectDirectory();
        FileObject parent = projDir;
        while (projDir != null) {
            projDir = getParentProjectDirectory(projDir);
            if (projDir != null) {
                parent = projDir;
            }
        }

        return parent;
    }

    public static Project getMainParent(Project proj) throws IOException {
        Project parent = getMavenProject(getParentProjectDirectory(proj.getProjectDirectory()));
        return parent != null ? parent : proj;
    }

    private static Set<FileObject> allModules(FileObject parent) {
        Set<FileObject> modules = Sets.newHashSet();
        modules.add(parent);

        for (FileObject fo : parent.getChildren()) {
            if (fo.isFolder() && fo.getFileObject("pom.xml") != null) {
                modules.addAll(allModules(fo));
            }
        }

        return modules;
    }

    public static List<? extends Project> getDependencyProjects(Project project){
        if (depProjects.get(project) == null) {
            depProjects.put(project, findDependencyProjects(project));
        }

        return depProjects.get(project);
    }

    private static List<? extends Project> findDependencyProjects(Project project){
        List<Project> dependencyProjects = new ArrayList<>();
        Object originalProject = getOriginalMavenProject(project);
        if (originalProject == null) {
            return dependencyProjects;
        }
        List<String> dependencies = Lists.newArrayList();
        try {
            Object compileDependencies = originalProject.getClass().getMethod("getCompileDependencies").invoke(originalProject);
            if (compileDependencies instanceof Collection) {
                for (Object dependency : (Collection<?>) compileDependencies) {
                    String artifactId = (String) dependency.getClass().getMethod("getArtifactId").invoke(dependency);
                    dependencies.add(artifactId);
                }
            }
        } catch (ReflectiveOperationException ex) {
            KotlinLogger.INSTANCE.logException("findDependencyProjects: getCompileDependencies failed", ex);
            return dependencyProjects;
        }

        FileObject mainParentFolder = getMainParentFolder(project);
        Set<FileObject> allModules = allModules(mainParentFolder);
        Set<FileObject> moduleDependencies = Sets.newHashSet();

        for (FileObject module : allModules) {
            if (dependencies.contains(module.getName())) {
                moduleDependencies.add(module);
            }
        }

        for (FileObject module : moduleDependencies) {
            try {
                Project dep = getMavenProject(module);
                if (dep != null) {
                    dependencyProjects.add(dep);
                }
            } catch (IOException ex) {
                KotlinLogger.INSTANCE.logException("Can't find module " + module.getName(), ex);
            }
        }

        return dependencyProjects;
    }

    /**
     * Returns the list of source directories declared in {@code kotlin-maven-plugin}'s
     * {@code <sourceDirs>} configuration, with {@code ${project.basedir}} resolved to
     * the project's base directory.
     *
     * <p>NetBeans reads the Maven POM statically and does not evaluate plugin bindings,
     * so source directories added by {@code kotlin-maven-plugin} at build time (e.g.
     * {@code src/main/kotlin}) are invisible to the standard {@link
     * org.netbeans.api.java.classpath.ClassPath#SOURCE} query. This method reads them
     * directly from the plugin configuration via reflection.
     *
     * <p>Both {@code <source>} and {@code <sourceDir>} child element names are supported
     * since different versions of the plugin use different names.
     *
     * @param proj the NetBeans project (must be a Maven project with
     *             {@code getOriginalMavenProject()} accessible via reflection)
     * @return deduplicated list of absolute paths; empty list if the plugin is absent
     *         or an error occurs
     */
    public static List<String> getKotlinPluginSourceDirs(Project proj) {
        Object mavenProject = getOriginalMavenProject(proj);
        if (mavenProject == null) return Collections.emptyList();
        try {
            File baseDir = (File) mavenProject.getClass().getMethod("getBasedir").invoke(mavenProject);
            String baseDirPath = baseDir.getAbsolutePath();

            Object buildPlugins = mavenProject.getClass().getMethod("getBuildPlugins").invoke(mavenProject);
            if (!(buildPlugins instanceof Collection)) return Collections.emptyList();

            Set<String> result = new LinkedHashSet<>();
            for (Object plugin : (Collection<?>) buildPlugins) {
                String gId = (String) plugin.getClass().getMethod("getGroupId").invoke(plugin);
                String aId = (String) plugin.getClass().getMethod("getArtifactId").invoke(plugin);
                if (!"org.jetbrains.kotlin".equals(gId) || !"kotlin-maven-plugin".equals(aId)) continue;

                Object executions = plugin.getClass().getMethod("getExecutions").invoke(plugin);
                if (!(executions instanceof Collection)) continue;
                for (Object exec : (Collection<?>) executions) {
                    Object config = exec.getClass().getMethod("getConfiguration").invoke(exec);
                    if (config == null) continue;
                    Object sourceDirsNode = config.getClass()
                            .getMethod("getChild", String.class).invoke(config, "sourceDirs");
                    if (sourceDirsNode == null) continue;
                    // kotlin-maven-plugin uses <source> or <sourceDir> depending on version
                    for (String childName : new String[]{"source", "sourceDir"}) {
                        Object[] children = (Object[]) sourceDirsNode.getClass()
                                .getMethod("getChildren", String.class).invoke(sourceDirsNode, childName);
                        for (Object child : children) {
                            String val = (String) child.getClass().getMethod("getValue").invoke(child);
                            if (val == null || val.isBlank()) continue;
                            val = val.replace("${project.basedir}", baseDirPath).trim();
                            File resolved = new File(val);
                            if (!resolved.isAbsolute()) resolved = new File(baseDir, val);
                            result.add(resolved.getAbsolutePath());
                        }
                    }
                }
            }
            return new ArrayList<>(result);
        } catch (ReflectiveOperationException ex) {
            KotlinLogger.INSTANCE.logException("getKotlinPluginSourceDirs failed", ex);
            return Collections.emptyList();
        }
    }

}
