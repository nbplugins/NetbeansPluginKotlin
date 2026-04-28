/*******************************************************************************
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
 *******************************************************************************/
package org.jetbrains.kotlin.projectsextensions.maven.classpath;

import org.jetbrains.kotlin.projectsextensions.ClassPathExtender;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;

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
