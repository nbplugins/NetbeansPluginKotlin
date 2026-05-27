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
package io.github.nbplugins.kotlin.nbm.projectsextensions.j2se;


import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.jetbrains.kotlin.utils.KotlinClasspath;
import org.jetbrains.kotlin.log.KotlinLogger;
import org.netbeans.api.project.Project;
import org.netbeans.spi.project.support.ant.EditableProperties;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;

/**
 * Modifies Ant-based J2SE project property files to integrate the Kotlin runtime
 * and disable compile-on-save (which conflicts with Kotlin incremental compilation).
 *
 * @author Alexander.Baratynski
 */
public class J2SEProjectPropertiesModifier {

    private final Project project;

    /**
     * @param project the J2SE project whose property files will be modified
     */
    public J2SEProjectPropertiesModifier(Project project) {
        this.project = project;
    }
    
    private String[] getModifiedClasspathProperty(EditableProperties editableProperties, String str) {
        List<String> classpath = new ArrayList<>();
        boolean hasKotlincClasspath = false;
        for (String item : Arrays.asList(editableProperties.getProperty(str).split(":"))) {
            classpath.add(item + ":");
            if (item.equals("${kotlinc.classpath}")) {
                hasKotlincClasspath = true;
            }
        }
        if (!hasKotlincClasspath) {
            classpath.add("${kotlinc.classpath}");
        }
        
        return classpath.toArray(new String[classpath.size()]);
    }
    
    /**
     * Appends {@code ${kotlinc.classpath}} to the project's {@code run.classpath} and
     * {@code javac.test.classpath} properties and records the Kotlin runtime JAR path.
     * No-ops when the project lacks {@code nbproject/project.properties}.
     */
    public void addKotlinRuntime() {
        FileObject root = project.getProjectDirectory();
        FileObject nbproject = root.getFileObject("nbproject");
        if (nbproject == null) {
            return;
        }
        
        FileObject projectProps = nbproject.getFileObject("project.properties");
        if (projectProps == null) {
            return;
        }
        
        EditableProperties editableProperties = new EditableProperties(true);
        try {
            InputStream input = projectProps.getInputStream();
            editableProperties.load(input);
            editableProperties.setProperty("run.classpath", getModifiedClasspathProperty(editableProperties, "run.classpath"));
            editableProperties.setProperty("javac.test.classpath", getModifiedClasspathProperty(editableProperties, "javac.test.classpath"));
            editableProperties.setProperty("file.reference.kotlin-runtime.jar", KotlinClasspath.INSTANCE.getKotlinBootClasspath());
            editableProperties.setProperty("kotlinc.classpath", "${file.reference.kotlin-runtime.jar}");
            input.close();
            OutputStream out = new BufferedOutputStream(new FileOutputStream(projectProps.getPath()));
            out.write("".getBytes());
            out.flush();
            editableProperties.store(out);
            out.close();
        } catch (FileNotFoundException ex) {
            KotlinLogger.INSTANCE.logException("No project.properties file!", ex);
        } catch (IOException ex) {
            KotlinLogger.INSTANCE.logException("", ex);
        }
    }
    
    /**
     * Sets {@code compile.on.save=false} in {@code nbproject/private/private.properties}.
     * No-ops when the file does not exist. Uses standard {@link Properties#load} instead of
     * {@code PropertyUtils} (which is inaccessible across NetBeans module classloaders).
     */
    public void turnOffCompileOnSave() {
        FileObject root = project.getProjectDirectory();
        FileObject nbproject = root.getFileObject("nbproject");
        if (nbproject == null) {
            return;
        }
        
        FileObject privateDir = nbproject.getFileObject("private");
        if (privateDir == null) {
            return;
        }
        
        FileObject privateProperties = privateDir.getFileObject("private.properties");
        if (privateProperties == null) {
            return;
        }
        
        
        try {
            Properties props = new Properties();
            try (InputStream is = privateProperties.getInputStream()) {
                props.load(is);
            }
            props.setProperty("compile.on.save", "false");
            FileWriter writer = new FileWriter(privateProperties.getPath());
            writer.write("");
            writer.flush();
            props.store(writer, "");
            writer.close();
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        } 
        
    }
    
}
