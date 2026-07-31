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
package org.jetbrains.kotlin.idea.k2.refactoring.pushDown

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo
import org.jetbrains.kotlin.psi.KtClass

/**
 * Public standalone entry point for IDEA's unchanged internal K2 Push Down processor.
 *
 * NetBeans cannot reference [K2PushDownProcessor] directly because IDEA intentionally marks it
 * `internal`. This bridge creates that processor in the same Kotlin module and invokes its normal
 * `BaseRefactoringProcessor.run()` lifecycle, including upstream usage discovery, conflict checks,
 * member insertion, marking, and source removal.
 *
 * @param project IDEA project associated with the active standalone K2 session.
 * @param sourceClass class whose selected members are pushed to its inheritors.
 * @param members selected IDEA member descriptors.
 */
class K2PushDownProcessorRunner(
    private val project: Project,
    private val sourceClass: KtClass,
    private val members: List<KotlinMemberInfo>,
) {
    /** Executes the original IDEA K2 Push Down processor lifecycle. */
    fun run() {
        K2PushDownProcessor(project, sourceClass, members).run()
    }
}
