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
package com.intellij.refactoring;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.SearchScope;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;

import java.util.Collection;
import java.util.Collections;

/**
 * Stub of IntelliJ's {@code com.intellij.refactoring.BaseRefactoringProcessor}.
 *
 * <p>Inline Variable's IDEA call chain
 * ({@code createReplacementStrategyForProperty} → {@code AbstractKotlinInlinePropertyProcessor.extractInitialization})
 * forces JVM linkage of {@code AbstractKotlinDeclarationInlineProcessor}, whose superclass is
 * this {@code BaseRefactoringProcessor}. The real class lives in
 * {@code platform/refactoring/src} of the IntelliJ community sources, but our build does not
 * compile those Java sources (joint-compilation is disabled in {@code KotlinRefactoring/pom.xml}
 * to keep the dependency surface minimal). Without a class file present at runtime, the JVM
 * throws {@link NoClassDefFoundError} the moment Inline Variable is invoked.
 *
 * <p>The plugin <strong>never instantiates</strong> any {@code BaseRefactoringProcessor} — IDEA's
 * "processor" pipeline is intentionally bypassed; we drive {@code CodeInliner} /
 * {@code PropertyUsageReplacementStrategy} directly from NetBeans flow. This stub only needs to
 * satisfy two things at link time:
 * <ul>
 *   <li>Provide the three-argument constructor ({@code Project, SearchScope, Runnable})
 *       called by Kotlin subclasses via {@code super(project, scope, null)}.</li>
 *   <li>Declare the abstract methods that Kotlin subclasses override
 *       ({@code findUsages}, {@code performRefactoring}, {@code createUsageViewDescriptor},
 *       {@code getCommandName}) so the JVM can resolve those overrides.</li>
 * </ul>
 *
 * <p>{@link #run()} (from {@link Runnable}) throws — calling code must never reach it; if it
 * does, that is a bug in the NetBeans-side inline flow, not in this stub.
 */
public abstract class BaseRefactoringProcessor implements Runnable {
    protected final Project myProject;
    protected final SearchScope myRefactoringScope;
    protected Runnable myPrepareSuccessfulSwingThreadCallback;

    protected BaseRefactoringProcessor(Project project) {
        this(project, null, null);
    }

    protected BaseRefactoringProcessor(Project project, Runnable prepareSuccessfulCallback) {
        this(project, null, prepareSuccessfulCallback);
    }

    protected BaseRefactoringProcessor(Project project, SearchScope refactoringScope, Runnable prepareSuccessfulCallback) {
        this.myProject = project;
        this.myRefactoringScope = refactoringScope;
        this.myPrepareSuccessfulSwingThreadCallback = prepareSuccessfulCallback;
    }

    protected abstract UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages);

    protected abstract UsageInfo[] findUsages();

    protected abstract void performRefactoring(UsageInfo[] usages);

    protected abstract String getCommandName();

    /**
     * Concrete in the real BRP; some Kotlin subclasses (e.g. {@code AbstractKotlinInlineNamedDeclarationProcessor})
     * override it. The override must have a target method in the superclass — keep the signature.
     */
    protected boolean preprocessUsages(Ref<UsageInfo[]> refUsages) {
        return true;
    }

    /**
     * Concrete in the real BRP; overridden by some Kotlin subclasses. Return an empty list here —
     * never invoked at runtime in our flow.
     */
    protected Collection<? extends PsiElement> getElementsToWrite(UsageViewDescriptor descriptor) {
        return Collections.emptyList();
    }

    protected void refreshElements(PsiElement[] elements) {
    }

    /**
     * Concrete in BRP; subclasses sometimes override it. Default to {@code true} (matches BRP's
     * behaviour for usages with non-null elements).
     */
    protected boolean isToBeChanged(UsageInfo usageInfo) {
        return true;
    }

    /** Setter used by some IDEA code paths. */
    public void setPrepareSuccessfulSwingThreadCallback(Runnable prepareSuccessfulSwingThreadCallback) {
        this.myPrepareSuccessfulSwingThreadCallback = prepareSuccessfulSwingThreadCallback;
    }

    /** Setter used by some IDEA code paths. */
    public void setPreviewUsages(boolean isPreviewUsages) {
    }

    /**
     * The {@link Runnable} contract on the real BRP triggers the whole modal refactoring pipeline.
     * In the NetBeans plugin we never let it run: Inline Variable wires the {@code CodeInliner}
     * engine directly via {@code KaInlineVariableComputer}. Reaching this point indicates a bug.
     */
    @Override
    public final void run() {
        throw new UnsupportedOperationException(
            "BaseRefactoringProcessor stub: the NetBeans Kotlin plugin does not run IDEA refactoring "
            + "processors directly. Inline Variable / Safe Delete drive the CodeInliner engine "
            + "through KaInlineVariableComputer instead.");
    }

    /**
     * Thrown by IDEA's ported {@code ExtractFunctionGenerator.buildSignature}/{@code
     * getDeclarationPattern} when the chosen {@code ExtractionTarget} is not available for the
     * descriptor (E9 Phase 2). The real class lives alongside {@link BaseRefactoringProcessor} in
     * {@code platform/refactoring/src}; declared here (constructor signature only, matching
     * upstream) purely so the JVM can resolve the exception-table entry the first time
     * {@code buildSignature}/{@code getDeclarationPattern} is invoked — the branch that actually
     * throws it is never expected to run for a plain function extraction with a valid descriptor.
     */
    public static final class ConflictsInTestsException extends RuntimeException {
        public ConflictsInTestsException(Collection<String> messages) {
            super(String.join("\n", messages));
        }
    }
}
