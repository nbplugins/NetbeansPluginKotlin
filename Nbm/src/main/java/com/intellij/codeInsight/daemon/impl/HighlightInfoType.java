/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package com.intellij.codeInsight.daemon.impl;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stub replacing the platform {@code HighlightInfoType} interface.
 *
 * The platform version calls {@code assertBundlePrecomputed()} in its static initializer,
 * which throws {@code AssertionError: Must be precomputed} in standalone NetBeans mode where
 * the IntelliJ bundle infrastructure is not available. This stub provides the minimal surface
 * used by {@code KotlinHighlightInfoTypeSemanticNames} and the K2 semantic highlighters:
 * the {@link #SYMBOL_TYPE_SEVERITY} constant and the {@link HighlightInfoTypeImpl} inner class.
 *
 * Loaded in preference to the JAR class because Nbm sources take classloader priority
 * over {@code ext/*.jar} — same mechanism used by {@code Registry}, {@code Formatter}, etc.
 */
public interface HighlightInfoType {

    /** Severity used for all Kotlin semantic token types. */
    HighlightSeverity SYMBOL_TYPE_SEVERITY = new HighlightSeverity("SYMBOL_TYPE_SEVERITY", -1);

    /**
     * Returns the severity level of this highlight type.
     *
     * @param element the PSI element being highlighted (may be {@code null})
     * @return the severity; never {@code null}
     */
    @NotNull HighlightSeverity getSeverity(@Nullable PsiElement element);

    /**
     * Returns the {@link TextAttributesKey} that controls the visual appearance of this type.
     *
     * @return the attributes key, or {@code null} if none was set
     */
    @Nullable TextAttributesKey getAttributesKey();

    /**
     * Whether this highlight needs to be recalculated on each keystroke.
     * Defaults to {@code true}; semantic highlights override this to {@code false}.
     *
     * @return {@code true} if recalculation is needed on typing
     */
    default boolean needsUpdateOnTyping() {
        return true;
    }

    /**
     * Concrete implementation of {@link HighlightInfoType} used by
     * {@code KotlinHighlightInfoTypeSemanticNames} to create Kotlin-specific highlight types.
     */
    class HighlightInfoTypeImpl implements HighlightInfoType {

        private final HighlightSeverity severity;
        private final TextAttributesKey attributesKey;

        /**
         * @param severity           the severity level
         * @param attributesKey      the text attributes key (may be {@code null})
         * @param needsUpdateOnTyping unused in this stub; kept for binary compatibility
         */
        public HighlightInfoTypeImpl(
                @NotNull HighlightSeverity severity,
                @Nullable TextAttributesKey attributesKey,
                boolean needsUpdateOnTyping) {
            this.severity = severity;
            this.attributesKey = attributesKey;
        }

        @Override
        public @NotNull HighlightSeverity getSeverity(@Nullable PsiElement element) {
            return severity;
        }

        @Override
        public @Nullable TextAttributesKey getAttributesKey() {
            return attributesKey;
        }

        @Override
        public boolean needsUpdateOnTyping() {
            return false;
        }
    }
}
