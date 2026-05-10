// Replacement for code-style-impl:241's FormatTextRanges.
// The original calls ContainerUtil.sorted(Collection,Comparator) which is absent from
// kotlin-compiler:1.9.25's shaded ContainerUtil. This copy replaces that call with
// ArrayList + Collections.sort, which is semantically identical.
package com.intellij.formatting;

import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.codeStyle.ChangedRangesInfo;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.TextRangeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class FormatTextRanges implements FormattingRangesInfo {

    private final List<TextRange> myInsertedRanges;
    private final List<FormatTextRange> myRanges;
    private final List<TextRange> myExtendedRanges;
    private final List<TextRange> myDisabledRanges;
    private boolean myExtendToContext;

    public FormatTextRanges() {
        myRanges = new ArrayList<>();
        myExtendedRanges = new ArrayList<>();
        myDisabledRanges = new ArrayList<>();
        myInsertedRanges = null;
    }

    public FormatTextRanges(@NotNull TextRange range, boolean processHeadingWhitespace) {
        myRanges = new ArrayList<>();
        myExtendedRanges = new ArrayList<>();
        myDisabledRanges = new ArrayList<>();
        myInsertedRanges = null;
        add(range, processHeadingWhitespace);
    }

    public FormatTextRanges(@NotNull ChangedRangesInfo changedRangesInfo,
                            @NotNull List<? extends TextRange> contextRanges) {
        myRanges = new ArrayList<>();
        myExtendedRanges = new ArrayList<>();
        myDisabledRanges = new ArrayList<>();
        myInsertedRanges = changedRangesInfo.insertedRanges;
        boolean first = true;
        for (TextRange range : contextRanges) {
            add(range, first);
            first = false;
        }
    }

    public void add(@NotNull TextRange range, boolean processHeadingWhitespace) {
        myRanges.add(new FormatTextRange(range, processHeadingWhitespace));
    }

    @Override
    public boolean isWhitespaceReadOnly(@NotNull TextRange range) {
        return ContainerUtil.and(myRanges, r -> r.isWhitespaceReadOnly(range));
    }

    @Override
    public boolean isReadOnly(@NotNull TextRange range) {
        return ContainerUtil.and(myRanges, r -> r.isReadOnly(range));
    }

    @Override
    public boolean isOnInsertedLine(int offset) {
        if (myInsertedRanges == null) return false;
        return myInsertedRanges.stream().filter(r -> r.contains(offset)).findAny().isPresent();
    }

    public List<FormatTextRange> getRanges() {
        return myRanges;
    }

    public FormatTextRanges ensureNonEmpty() {
        FormatTextRanges result = new FormatTextRanges();
        for (FormatTextRange r : myRanges) {
            if (r.isProcessHeadingWhitespace()) {
                result.add(r.getNonEmptyTextRange(), true);
            } else {
                result.add(r.getTextRange(), false);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return myRanges.isEmpty();
    }

    public boolean isFullReformat(com.intellij.psi.PsiFile file) {
        return myRanges.size() == 1 && file.getTextRange().equals(myRanges.get(0).getTextRange());
    }

    @Override
    @NotNull
    public List<TextRange> getTextRanges() {
        List<TextRange> result = ContainerUtil.map(myRanges, FormatTextRange::getTextRange);
        List<TextRange> sorted = new ArrayList<>(result);
        Collections.sort(sorted, Segment.BY_START_OFFSET_THEN_END_OFFSET);
        return sorted;
    }

    public void setExtendedRanges(@NotNull List<? extends TextRange> extendedRanges) {
        myExtendedRanges.addAll(extendedRanges);
    }

    public List<TextRange> getExtendedRanges() {
        return myExtendedRanges.isEmpty() ? getTextRanges() : myExtendedRanges;
    }

    @Override
    @Nullable
    public TextRange getBoundRange() {
        List<TextRange> ranges = getTextRanges();
        if (ranges.isEmpty()) return null;
        return new TextRange(ranges.get(0).getStartOffset(), ranges.get(ranges.size() - 1).getEndOffset());
    }

    public boolean isExtendToContext() {
        return myExtendToContext;
    }

    public void setExtendToContext(boolean value) {
        myExtendToContext = value;
    }

    public void setDisabledRanges(@NotNull Collection<? extends TextRange> disabledRanges) {
        myDisabledRanges.clear();
        List<TextRange> sorted = new ArrayList<>(disabledRanges);
        Collections.sort(sorted, Segment.BY_START_OFFSET_THEN_END_OFFSET);
        myDisabledRanges.addAll(sorted);
    }

    public boolean isInDisabledRange(@NotNull TextRange textRange) {
        return TextRangeUtil.intersectsOneOf(textRange, myDisabledRanges);
    }
}
