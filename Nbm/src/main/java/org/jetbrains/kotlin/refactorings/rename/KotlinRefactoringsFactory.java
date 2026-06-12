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
import javax.swing.text.StyledDocument;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
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
        return null;
    }
}
