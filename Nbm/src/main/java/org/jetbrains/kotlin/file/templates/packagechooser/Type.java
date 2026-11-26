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
 *
 *******************************************************************************/
package org.jetbrains.kotlin.file.templates.packagechooser;

/**
 * Object kind created by a Kotlin New File wizard, which decides how the name entered in the
 * target chooser is interpreted.
 *
 * @author Alexander.Baratynski
 */
public enum Type {

    /** A Kotlin class file; a dotted name is split into a package and a class name. */
    FILE(false),

    /** A package directory. */
    PACKAGE(false),

    /** The {@code package-info} file of a package. */
    PKG_INFO(false),

    /**
     * A Kotlin script file; the entered name is a file name, so dots stay part of it:
     * {@code demo.main} produces {@code demo.main.kts}.
     */
    SCRIPT(true);

    private final boolean literalFileName;

    Type(boolean literalFileName) {
        this.literalFileName = literalFileName;
    }

    /**
     * Tells whether the name entered in the target chooser must be used as a file name
     * verbatim instead of being parsed as a dotted class name.
     *
     * @return {@code true} for types whose name is a literal file name
     */
    public boolean isLiteralFileName() {
        return literalFileName;
    }
}
