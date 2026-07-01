/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package org.jetbrains.kotlin.idea.refactoring.rename

import org.jetbrains.kotlin.idea.references.KtReference

/**
 * Local stub of `isKotlinAwareJavaGetterRename` from IDEA's `KotlinAwareJavaGetterRenameProcessor`.
 *
 * The real function checks a `RENAME_JAVA_GETTER_MARKER` user-data key set by IDEA's
 * `RenameJavaMethodProcessor` machinery (not on the standalone classpath).  The marker is only ever
 * set during a Java-getter rename refactoring; Copy Declaration never triggers that path, so this
 * stub always returns `false`, selecting the ordinary-method branch in
 * `KtReferenceMutateServiceBase.renameToOrdinaryMethod`.  This avoids pulling in the whole
 * `RenameJavaMethodProcessor` dependency.
 */
internal fun isKotlinAwareJavaGetterRename(ref: KtReference): Boolean = false
