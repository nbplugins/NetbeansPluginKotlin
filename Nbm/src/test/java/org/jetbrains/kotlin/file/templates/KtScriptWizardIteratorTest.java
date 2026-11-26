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

import org.jetbrains.kotlin.file.templates.defaultwizard.KtDefaultWizardIterator;
import org.netbeans.junit.NbTestCase;
import org.openide.WizardDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Tests the New File → Kotlin → Kotlin script template registration and its rendering.
 *
 * <p>The layer entry is hand-written (annotation processing is disabled in this build), so the
 * test guards the entry name, the template extension and the iterator class against typos. The
 * generated file must keep the {@code kts} extension because that is what maps it to
 * {@code text/x-kotlin}.
 */
public final class KtScriptWizardIteratorTest extends NbTestCase {

    /**
     * Creates the test fixture.
     *
     * @param name test method name
     */
    public KtScriptWizardIteratorTest(String name) {
        super(name);
    }

    /** The template is registered in the Kotlin New File category with the script iterator. */
    public void testLayerRegistration() {
        FileObject category = FileUtil.getConfigFile("Templates/Kotlin");
        assertNotNull("Templates/Kotlin must be registered", category);

        FileObject template = category.getFileObject("script.kts");
        assertNotNull("Templates/Kotlin/script.kts must be registered", template);
        assertEquals(Boolean.TRUE, template.getAttribute("template"));

        Object iterator = template.getAttribute("instantiatingIterator");
        assertTrue("script.kts must instantiate a wizard iterator, was " + iterator,
                iterator instanceof WizardDescriptor.InstantiatingIterator);
        assertEquals(KtScriptWizardIterator.class, iterator.getClass());
        assertNotNull("script.kts must have a description",
                template.getAttribute("instantiatingWizardURL"));
    }

    /** The template content is a valid Kotlin script with no unresolved template directives. */
    public void testTemplateContent() throws Exception {
        FileObject template = FileUtil.getConfigFile("Templates/Kotlin/script.kts");
        assertNotNull(template);

        String body = template.asText("UTF-8");
        String rendered = KtDefaultWizardIterator.renderTemplate(body, "com.example", "demo");

        assertEquals("the script template must not need package/name placeholders",
                body, rendered);
        assertFalse("no template directives may reach the generated script",
                rendered.contains("${"));
        assertTrue("the generated script must contain the sample statement",
                rendered.contains("println(\"Hello, Kotlin!\")"));
    }

    /** The generated file keeps the script extension. */
    public void testTemplateExtensionIsKts() {
        FileObject template = FileUtil.getConfigFile("Templates/Kotlin/script.kts");
        assertNotNull(template);
        assertEquals("kts", template.getExt());
    }

    /** The iterator creates {@code .kts} files and needs a project. */
    public void testIteratorIsConstructible() {
        KtScriptWizardIterator iterator = new KtScriptWizardIterator();
        assertTrue(iterator instanceof WizardDescriptor.InstantiatingIterator);
    }
}
