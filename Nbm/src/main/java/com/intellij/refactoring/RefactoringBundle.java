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
package com.intellij.refactoring;

/**
 * Standalone message-bundle bridge for copied IDEA refactoring processors.
 *
 * NetBeans supplies all visible refactoring text. The K2 engine only needs non-null strings for
 * progress and conflict lifecycle contracts, so returning the bundle key avoids loading IDEA's
 * resource bundle infrastructure.
 */
public final class RefactoringBundle {
    private RefactoringBundle() {
    }

    /**
     * Returns a stable standalone representation of an IDEA message.
     *
     * @param key IDEA resource-bundle key.
     * @param params interpolation arguments, unused by the standalone bridge.
     * @return the key.
     */
    public static String message(String key, Object... params) {
        return key;
    }

    /**
     * Returns a message without interpolation arguments.
     *
     * @param key IDEA resource-bundle key.
     * @return the key.
     */
    public static String message(String key) {
        return key;
    }
}
