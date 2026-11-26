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
package org.jetbrains.kotlin.file.templates.packagechooser;

import org.netbeans.junit.NbTestCase;

/**
 * Tests how the target chooser interprets the entered name for each {@link Type}.
 *
 * <p>Kotlin scripts follow the Kotlin scripting convention that dots are part of the file name
 * ({@code main.main.kts}), so a script name must never be split into a package and a class name
 * the way {@link Type#FILE} does.
 */
public final class TargetChooserPanelTest extends NbTestCase {

    /**
     * Creates the test fixture.
     *
     * @param name test method name
     */
    public TargetChooserPanelTest(String name) {
        super(name);
    }

    /** A script name is a file name: dots stay in it. */
    public void testScriptNameKeepsDots() {
        assertEquals("demo.main", TargetChooserPanel.targetName(Type.SCRIPT, "demo.main"));
        assertEquals(".main", TargetChooserPanel.targetName(Type.SCRIPT, ".main"));
        assertEquals("my-script", TargetChooserPanel.targetName(Type.SCRIPT, "my-script"));
        assertEquals("plain", TargetChooserPanel.targetName(Type.SCRIPT, "plain"));
    }

    /** Class names keep the fully qualified name behaviour. */
    public void testClassNameIsSplitAtTheLastDot() {
        assertEquals("main", TargetChooserPanel.targetName(Type.FILE, "demo.main"));
        assertEquals("Foo", TargetChooserPanel.targetName(Type.FILE, "com.example.Foo"));
        assertEquals("Foo", TargetChooserPanel.targetName(Type.FILE, "Foo"));
    }

    /** Package names are taken verbatim. */
    public void testPackageNameIsVerbatim() {
        assertEquals("com.example", TargetChooserPanel.targetName(Type.PACKAGE, "com.example"));
    }

    /** Only {@link Type#SCRIPT} treats the entered name as a literal file name. */
    public void testOnlyScriptUsesLiteralFileNames() {
        assertTrue(Type.SCRIPT.isLiteralFileName());
        assertFalse(Type.FILE.isLiteralFileName());
        assertFalse(Type.PACKAGE.isLiteralFileName());
        assertFalse(Type.PKG_INFO.isLiteralFileName());
    }

    /** Script names only have to be legal file names, not Java identifiers. */
    public void testScriptFileNameValidation() {
        assertTrue(TargetChooserPanel.isValidScriptFileName("demo.main"));
        assertTrue(TargetChooserPanel.isValidScriptFileName("my-script"));
        assertTrue(TargetChooserPanel.isValidScriptFileName(".main"));
        assertTrue(TargetChooserPanel.isValidScriptFileName("build.main"));

        assertFalse(TargetChooserPanel.isValidScriptFileName(null));
        assertFalse(TargetChooserPanel.isValidScriptFileName(""));
        assertFalse(TargetChooserPanel.isValidScriptFileName("."));
        assertFalse(TargetChooserPanel.isValidScriptFileName(".."));
        assertFalse(TargetChooserPanel.isValidScriptFileName("dir/script"));
        assertFalse(TargetChooserPanel.isValidScriptFileName("dir\\script"));
        assertFalse(TargetChooserPanel.isValidScriptFileName("bad\u0000name"));
        assertFalse(TargetChooserPanel.isValidScriptFileName("a:b"));
    }
}
