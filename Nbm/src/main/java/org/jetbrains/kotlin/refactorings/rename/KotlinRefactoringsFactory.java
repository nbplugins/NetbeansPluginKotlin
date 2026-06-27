/**
 * *****************************************************************************
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
 ******************************************************************************
 */
package org.jetbrains.kotlin.refactorings.rename;

import io.github.nbplugins.kotlin.nbm.navigation.KotlinWhereUsedPlugin;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinExtractFunctionPlugin;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinExtractFunctionRefactoring;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinInlineFunctionPlugin;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinInlineFunctionRefactoring;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinInlineVariablePlugin;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinInlineVariableRefactoring;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinIntroduceConstantPlugin;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinIntroduceConstantRefactoring;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinIntroduceVariablePlugin;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinIntroduceVariableRefactoring;
import io.github.nbplugins.kotlin.nbm.refactoring.KotlinSafeDeletePlugin;
import javax.swing.text.StyledDocument;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.api.SafeDeleteRefactoring;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.spi.RefactoringPlugin;
import org.netbeans.modules.refactoring.spi.RefactoringPluginFactory;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;


/**
 * Factory that creates Kotlin-specific {@link RefactoringPlugin}s for supported refactorings.
 *
 * Registered in {@code layer.xml} under {@code Services/} because annotation processing is
 * disabled by {@code -proc:none} and {@code @ServiceProvider} alone does not generate
 * {@code META-INF/services} entries in this mixed Kotlin+Java build.
 *
 * Supported refactoring types:
 * <ul>
 *   <li>{@link RenameRefactoring} — delegates to {@link KotlinRenameRefactoring}</li>
 *   <li>{@link WhereUsedQuery} — delegates to {@link KotlinWhereUsedPlugin}</li>
 *   <li>{@link SafeDeleteRefactoring} — delegates to {@link KotlinSafeDeletePlugin}</li>
 *   <li>{@link KotlinInlineVariableRefactoring} — delegates to {@link KotlinInlineVariablePlugin}</li>
 *   <li>{@link KotlinInlineFunctionRefactoring} — delegates to {@link KotlinInlineFunctionPlugin}</li>
 *   <li>{@link KotlinIntroduceVariableRefactoring} — delegates to {@link KotlinIntroduceVariablePlugin}</li>
 *   <li>{@link KotlinExtractFunctionRefactoring} — delegates to {@link KotlinExtractFunctionPlugin}</li>
 *   <li>{@link KotlinIntroduceConstantRefactoring} — delegates to {@link KotlinIntroduceConstantPlugin}</li>
 * </ul>
 */
@ServiceProvider(service = RefactoringPluginFactory.class, position = 100)
public class KotlinRefactoringsFactory implements RefactoringPluginFactory {

    @Override
    public RefactoringPlugin createInstance(AbstractRefactoring refactoring) {
        FileObject fo = ProjectUtils.getFileObjectForDocument(
                refactoring.getRefactoringSource().lookup(StyledDocument.class));
        if (fo == null || !fo.hasExt("kt")) {
            return null;
        }
        if (refactoring instanceof RenameRefactoring) {
            return new KotlinRenameRefactoring((RenameRefactoring) refactoring);
        }
        if (refactoring instanceof WhereUsedQuery) {
            return new KotlinWhereUsedPlugin((WhereUsedQuery) refactoring);
        }
        if (refactoring instanceof SafeDeleteRefactoring) {
            return new KotlinSafeDeletePlugin((SafeDeleteRefactoring) refactoring);
        }
        if (refactoring instanceof KotlinInlineVariableRefactoring) {
            return new KotlinInlineVariablePlugin((KotlinInlineVariableRefactoring) refactoring);
        }
        if (refactoring instanceof KotlinInlineFunctionRefactoring) {
            return new KotlinInlineFunctionPlugin((KotlinInlineFunctionRefactoring) refactoring);
        }
        if (refactoring instanceof KotlinIntroduceVariableRefactoring) {
            return new KotlinIntroduceVariablePlugin((KotlinIntroduceVariableRefactoring) refactoring);
        }
        if (refactoring instanceof KotlinExtractFunctionRefactoring) {
            return new KotlinExtractFunctionPlugin((KotlinExtractFunctionRefactoring) refactoring);
        }
        if (refactoring instanceof KotlinIntroduceConstantRefactoring) {
            return new KotlinIntroduceConstantPlugin((KotlinIntroduceConstantRefactoring) refactoring);
        }
        return null;
    }
}
