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
package org.jetbrains.kotlin.structurescanner

import io.github.nbplugins.kotlin.nbm.structurescanner.KaStructureScanner
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.csl.api.StructureItem
import org.netbeans.modules.csl.api.StructureScanner
import org.netbeans.modules.csl.spi.ParserResult
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.StructureScanner.Configuration

class KotlinStructureScanner : StructureScanner {

    override fun getConfiguration() = Configuration(true, true)

    override fun scan(info: ParserResult): List<StructureItem> {
        val file = info.snapshot.source.fileObject ?: return emptyList()
        val project = ProjectUtils.getKotlinProjectForFileObject(file) ?: return emptyList()
        return KaStructureScanner.getStructureItems(file, project) ?: emptyList()
    }

    override fun folds(info: ParserResult): Map<String, List<OffsetRange>> {
        val file = info.snapshot.source.fileObject ?: return emptyMap()
        return foldMap(file)
    }

    fun foldMap(file: org.openide.filesystems.FileObject): Map<String, List<OffsetRange>> {
        if (ProjectUtils.getKotlinProjectForFileObject(file) == null) return emptyMap()
        val ktFile = ProjectUtils.getKtFile(file) ?: return emptyMap()
        return KotlinFoldingVisitor(ktFile).computeFolds()
    }

}
