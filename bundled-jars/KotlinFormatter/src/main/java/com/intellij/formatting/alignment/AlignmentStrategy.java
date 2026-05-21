// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Compile-only stub: AlignmentStrategy is not published in any 242-era Maven artifact.
// The real implementation ships inside the IDE JARs; this stub satisfies the Kotlin compiler
// when building KotlinFormatter from sources. At NetBeans runtime the formatter classes are
// loaded against the full kotlin-compiler-ir-for-ide which supplies its own copy.
package com.intellij.formatting.alignment;

import com.intellij.lang.ASTNode;
import com.intellij.psi.tree.IElementType;
import com.intellij.formatting.Alignment;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class AlignmentStrategy {

    @Nullable
    public abstract Alignment getAlignment(IElementType parentType, IElementType childType);

    @Nullable
    public abstract Alignment getAlignment(IElementType elementType);

    public static AlignmentStrategy wrap(@Nullable Alignment alignment) {
        throw new UnsupportedOperationException("stub");
    }

    public static AlignmentStrategy createAlignmentPerTypeStrategy(
            List<? extends IElementType> tokenTypes,
            IElementType parentType,
            boolean forFirstElement) {
        throw new UnsupportedOperationException("stub");
    }
}
