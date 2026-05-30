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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.projectsextensions.maven.classpath;

import io.github.nbplugins.kotlin.nbm.projectsextensions.maven.MavenHelper;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.kotlin.projectsextensions.ClassPathExtender;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Provides classpath information for Maven projects using only NetBeans APIs,
 * avoiding direct references to Maven internals that are not accessible from
 * the plugin's classloader (e.g. DependencyResolutionRequiredException).
 */
public class MavenExtendedClassPath implements ClassPathExtender {

    private final Project project;
    private ClassPath boot;
    private ClassPath compile;
    private ClassPath execute;
    private ClassPath source;

    public MavenExtendedClassPath(Project project) {
        this.project = project;
        createClasspath();
    }

    private FileObject findSourceRoot() {
        Sources sources = project.getLookup().lookup(Sources.class);
        if (sources != null) {
            for (SourceGroup sg : sources.getSourceGroups("java")) {
                FileObject root = sg.getRootFolder();
                if (root != null) return root;
            }
        }
        return project.getProjectDirectory();
    }

    private void createClasspath() {
        ClassPathProvider provider = project.getLookup().lookup(ClassPathProvider.class);
        if (provider != null) {
            FileObject context = findSourceRoot();
            boot    = provider.findClassPath(context, ClassPath.BOOT);
            compile = provider.findClassPath(context, ClassPath.COMPILE);
            source  = provider.findClassPath(context, ClassPath.SOURCE);
            source  = augmentWithKotlinSourceDirs(source);
            execute = provider.findClassPath(context, ClassPath.EXECUTE);
        }

        // If the platform's bootstrap libraries are richer, prefer them for boot.
        JavaPlatform javaPlatform = JavaPlatform.getDefault();
        if (javaPlatform != null) {
            ClassPath platformBoot = javaPlatform.getBootstrapLibraries();
            if (boot != null) {
                boot = ClassPathSupport.createProxyClassPath(boot, platformBoot);
            } else {
                boot = platformBoot;
            }
        }

        if (boot    == null) boot    = ClassPath.EMPTY;
        if (compile == null) compile = ClassPath.EMPTY;
        if (source  == null) source  = ClassPath.EMPTY;
        if (execute == null) execute = ClassPath.EMPTY;
    }

    /**
     * Augments the given SOURCE classpath with source directories declared in
     * {@code kotlin-maven-plugin}'s {@code <sourceDirs>} configuration that are
     * not already present in the classpath.
     *
     * <p>The NetBeans Maven module reads the POM statically and does not evaluate
     * plugin bindings, so directories added by {@code kotlin-maven-plugin} (e.g.
     * {@code src/main/kotlin}) are absent from the standard SOURCE classpath.
     * This method fills that gap so the K2 analysis session receives all source roots.
     *
     * @param sourceClassPath the initial SOURCE classpath from the Maven
     *                        {@link ClassPathProvider}
     * @return the augmented classpath, or the original if no extra roots were found
     */
    private ClassPath augmentWithKotlinSourceDirs(ClassPath sourceClassPath) {
        List<String> dirs = MavenHelper.getKotlinPluginSourceDirs(project);
        if (dirs.isEmpty()) return sourceClassPath;

        Set<URL> existing = new HashSet<>();
        for (ClassPath.Entry e : sourceClassPath.entries()) existing.add(e.getURL());

        List<FileObject> extra = new ArrayList<>();
        for (String dir : dirs) {
            FileObject fo = FileUtil.toFileObject(new File(dir));
            if (fo != null && fo.isFolder() && !existing.contains(fo.toURL())) {
                extra.add(fo);
            }
        }
        if (extra.isEmpty()) return sourceClassPath;
        ClassPath extraCp = ClassPathSupport.createClassPath(extra.toArray(new FileObject[0]));
        return ClassPathSupport.createProxyClassPath(sourceClassPath, extraCp);
    }

    @Override
    public ClassPath getProjectSourcesClassPath(String type) {
        switch (type) {
            case ClassPath.COMPILE: return compile;
            case ClassPath.EXECUTE: return execute;
            case ClassPath.SOURCE:  return source;
            case ClassPath.BOOT:    return boot;
            default:                return ClassPath.EMPTY;
        }
    }
}
