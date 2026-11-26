/** *****************************************************************************
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
 ****************************************************************************** */
package org.jetbrains.kotlin.file.templates;

import org.jetbrains.kotlin.file.templates.defaultwizard.KtDefaultWizardIterator;
import org.netbeans.api.templates.TemplateRegistration;

/**
 * New File wizard for Kotlin scripts.
 *
 * <p>The base class renders {@code ${package}} and {@code ${name}} itself and creates the file
 * with the template's extension, so the generated file is {@code <name>.kts}; the layer resolver
 * maps that extension to {@code text/x-kotlin}, which makes the Kotlin CSL editor (highlighting,
 * folding, formatting) apply.
 */
@TemplateRegistration(folder = "Kotlin",
        displayName = "Kotlin script",
        content = "script.kts",
        iconBase = "org/jetbrains/kotlin/kt.png",
        description = "ktScript.html",
        scriptEngine = "freemarker")
public class KtScriptWizardIterator extends KtDefaultWizardIterator {

    /** Creates the wizard. */
    public KtScriptWizardIterator() {
        super("Script");
    }
}
