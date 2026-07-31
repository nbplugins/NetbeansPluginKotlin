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
package org.jetbrains.kotlin.idea.core.util

import com.intellij.openapi.project.Project

/**
 * Runs an IDEA progress task synchronously in standalone NetBeans mode.
 *
 * NetBeans owns the visible refactoring progress UI. The copied IDEA K2 engine only needs the
 * result-bearing lifecycle contract, so this compatibility bridge executes [action] directly.
 *
 * @param progressTitle IDEA progress text, intentionally not displayed here.
 * @param canBeCanceled whether IDEA would permit cancellation, unused in this non-modal bridge.
 * @param action operation to execute.
 * @return the action result.
 */
fun <T : Any> Project.runSynchronouslyWithProgress(
    @Suppress("UNUSED_PARAMETER") progressTitle: String,
    @Suppress("UNUSED_PARAMETER") canBeCanceled: Boolean,
    action: () -> T,
): T = action()
