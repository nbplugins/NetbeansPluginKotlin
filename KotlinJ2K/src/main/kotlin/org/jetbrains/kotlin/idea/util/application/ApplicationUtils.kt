/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package org.jetbrains.kotlin.idea.util.application

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable

/**
 * Standalone replacement for the IDEA `ApplicationUtils.kt` helpers that the J2K engine uses.
 *
 * Only the two `executeWriteCommand` overloads are reproduced (verbatim semantics: one undoable
 * command, one write action, result returned). The rest of IDEA's file — `runReadAction`,
 * `invokeLater`, `CancellationCheck`, `ApplicationEx` helpers — is not used by the J2K sources and
 * would require IDE-only classes.
 */

/**
 * Runs [command] as one undoable write action.
 *
 * @param name the command name shown in the undo stack.
 * @param command the action to run.
 */
fun Project.executeWriteCommand(name: String, command: () -> Unit) {
    CommandProcessor.getInstance().executeCommand(this, { runWriteAction(command) }, name, null)
}

/**
 * Runs [command] as one undoable write action and returns its result.
 *
 * @param name the command name shown in the undo stack.
 * @param groupId the undo group id, or `null` for a new group.
 * @param command the action to run.
 * @return the value returned by [command].
 */
fun <T> Project.executeWriteCommand(name: String, groupId: Any? = null, command: () -> T): T {
    @Suppress("UNCHECKED_CAST")
    var result: T = null as T
    CommandProcessor.getInstance().executeCommand(this, { result = runWriteAction(command) }, name, groupId)
    @Suppress("USELESS_CAST")
    return result as T
}

/**
 * Runs [action] in a write action and returns its result.
 *
 * @param action the action to run.
 * @return the value returned by [action].
 */
private fun <T> runWriteAction(action: () -> T): T =
    ApplicationManager.getApplication().runWriteAction(Computable { action() })
