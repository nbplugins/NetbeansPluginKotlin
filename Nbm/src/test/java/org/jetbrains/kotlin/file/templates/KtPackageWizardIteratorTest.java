/*******************************************************************************
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
package org.jetbrains.kotlin.file.templates;

import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectSourceGroups;
import java.beans.PropertyChangeListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.event.ChangeListener;
import org.jetbrains.kotlin.file.templates.defaultwizard.KtDefaultWizardIterator;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.junit.NbTestCase;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/** Tests Kotlin package-wizard source selection and folder creation. */
public final class KtPackageWizardIteratorTest extends NbTestCase {

    /** Creates the test fixture. */
    public KtPackageWizardIteratorTest(String name) {
        super(name);
    }

    /** Selects standard and Kotlin source groups, without resources or duplicate roots. */
    public void testKotlinPackageGroupsIncludeStandardAndKotlinRoots() throws Exception {
        clearWorkDir();
        FileObject root = FileUtil.toFileObject(getWorkDir());
        FileObject javaMain = FileUtil.createFolder(root, "src/main/java");
        FileObject javaTest = FileUtil.createFolder(root, "src/test/java");
        FileObject kotlinMain = FileUtil.createFolder(root, "src/main/kotlin");
        FileObject kotlinTest = FileUtil.createFolder(root, "src/test/kotlin");
        FileObject resources = FileUtil.createFolder(root, "src/main/resources");
        TestSources sources = new TestSources();
        sources.add(JavaProjectConstants.SOURCES_TYPE_JAVA, new TestSourceGroup(javaMain, "Source Packages"));
        sources.add(JavaProjectConstants.SOURCES_TYPE_JAVA, new TestSourceGroup(javaTest, "Test Packages"));
        sources.add("resources", new TestSourceGroup(resources, "Resources"));
        sources.add(KotlinProjectSourceGroups.KOTLIN_SOURCE_TYPE,
                new TestSourceGroup(kotlinMain, KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES));
        sources.add(KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE,
                new TestSourceGroup(kotlinTest, KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES));
        sources.add(KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE,
                new TestSourceGroup(javaMain, "Duplicate Source Packages"));

        List<SourceGroup> groups = KtDefaultWizardIterator.kotlinPackageSourceGroups(new TestProject(root, sources));

        assertEquals(List.of(javaMain, javaTest, kotlinMain, kotlinTest),
                groups.stream().map(SourceGroup::getRootFolder).toList());
        assertEquals(List.of(
                "Source Packages",
                "Test Packages",
                KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES,
                KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES),
                groups.stream().map(SourceGroup::getDisplayName).toList());
    }

    /** Keeps standard Source Packages and Test Packages available when no Kotlin root is registered. */
    public void testKotlinPackageGroupsIncludeJavaOnlyProjectRoots() throws Exception {
        clearWorkDir();
        FileObject root = FileUtil.toFileObject(getWorkDir());
        FileObject javaMain = FileUtil.createFolder(root, "src/main/java");
        FileObject javaTest = FileUtil.createFolder(root, "src/test/java");
        TestSources sources = new TestSources();
        sources.add(JavaProjectConstants.SOURCES_TYPE_JAVA, new TestSourceGroup(javaMain, "Source Packages"));
        sources.add(JavaProjectConstants.SOURCES_TYPE_JAVA, new TestSourceGroup(javaTest, "Test Packages"));

        List<SourceGroup> groups = KtDefaultWizardIterator.kotlinPackageSourceGroups(new TestProject(root, sources));

        assertEquals(List.of(javaMain, javaTest), groups.stream().map(SourceGroup::getRootFolder).toList());
    }

    /** Resolves the manually registered Kotlin Package template to its iterator. */
    public void testKotlinPackageTemplateRegistersIterator() {
        FileObject template = FileUtil.getConfigFile("Templates/Kotlin/Package");

        assertNotNull("Kotlin Package template must be registered in the layer", template);
        assertEquals("Kotlin Package", template.getAttribute("displayName"));
        assertEquals(Boolean.TRUE, template.getAttribute("requireProject"));
        assertTrue(template.getAttribute("instantiatingIterator") instanceof KtPackageWizardIterator);
    }

    /** Creates a nested package below the selected Kotlin root and reuses existing parent folders. */
    public void testCreateKotlinPackageFolderCreatesNestedFolders() throws Exception {
        clearWorkDir();
        FileObject root = FileUtil.toFileObject(getWorkDir());
        FileObject kotlinRoot = FileUtil.createFolder(root, "src/test/kotlin/com");

        FileObject created = KtDefaultWizardIterator.createPackageFolder(kotlinRoot.getParent(), "com.example.feature");

        assertEquals("src/test/kotlin/com/example/feature", created.getPath().substring(root.getPath().length() + 1));
        assertTrue(created.isFolder());
        assertNotNull(kotlinRoot.getParent().getFileObject("com/example/feature"));
    }

    /** Minimal test project exposing one source provider. */
    private static final class TestProject implements Project {
        private final FileObject directory;
        private final Lookup lookup;

        /** Creates a project rooted at {@code directory}. */
        TestProject(FileObject directory, Sources sources) {
            this.directory = directory;
            this.lookup = Lookups.fixed(this, sources);
        }

        /** Returns the project directory. */
        @Override
        public FileObject getProjectDirectory() {
            return directory;
        }

        /** Returns the project lookup. */
        @Override
        public Lookup getLookup() {
            return lookup;
        }
    }

    /** Simple map-backed source provider. */
    private static final class TestSources implements Sources {
        private final Map<String, List<SourceGroup>> groups = new LinkedHashMap<>();

        /** Adds {@code group} to source groups registered for {@code type}. */
        void add(String type, SourceGroup group) {
            groups.computeIfAbsent(type, ignored -> new java.util.ArrayList<>()).add(group);
        }

        /** Returns the configured source groups. */
        @Override
        public SourceGroup[] getSourceGroups(String type) {
            return groups.getOrDefault(type, List.of()).toArray(new SourceGroup[0]);
        }

        /** This immutable provider has no change notifications. */
        @Override
        public void addChangeListener(ChangeListener listener) {
        }

        /** This immutable provider has no change notifications. */
        @Override
        public void removeChangeListener(ChangeListener listener) {
        }
    }

    /** Minimal immutable source group. */
    private static final class TestSourceGroup implements SourceGroup {
        private final FileObject root;
        private final String label;

        /** Creates a source group for {@code root}. */
        TestSourceGroup(FileObject root, String label) {
            this.root = root;
            this.label = label;
        }

        /** Returns the group root. */
        @Override
        public FileObject getRootFolder() {
            return root;
        }

        /** Returns a stable group name. */
        @Override
        public String getName() {
            return root.getPath();
        }

        /** Returns the display label. */
        @Override
        public String getDisplayName() {
            return label;
        }

        /** This test group has no icon. */
        @Override
        public Icon getIcon(boolean opened) {
            return null;
        }

        /** Tests whether {@code file} is under this group root. */
        @Override
        public boolean contains(FileObject file) {
            return file.equals(root) || file.getPath().startsWith(root.getPath() + "/");
        }

        /** This immutable group has no change notifications. */
        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
        }

        /** This immutable group has no change notifications. */
        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
        }
    }
}
