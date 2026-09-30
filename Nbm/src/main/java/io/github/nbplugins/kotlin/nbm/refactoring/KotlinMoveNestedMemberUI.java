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
package io.github.nbplugins.kotlin.nbm.refactoring;

import io.github.nbplugins.kotlin.refactoring.KaMoveNestedMemberComputer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * Passive NetBeans view for choosing a Move Nested Member destination.
 *
 * It receives K2 discovery data from its controller and writes only the selected file/offset to the
 * carrier; semantic conflict checks remain in {@link KotlinMoveNestedMemberPlugin}.
 */
public final class KotlinMoveNestedMemberUI implements RefactoringUI {
    private final KaMoveNestedMemberComputer.Outcome.Ready ready;
    private final List<KaMoveNestedMemberComputer.TargetCandidate> candidates;
    private final KotlinMoveNestedMemberRefactoring refactoring;
    private TargetPanel panel;

    /**
     * Creates a target-selection view.
     *
     * @param ready source declaration presentation from the K2 controller
     * @param candidates compatible targets computed by the K2 controller
     * @param refactoring carrier populated on confirmation
     */
    public KotlinMoveNestedMemberUI(KaMoveNestedMemberComputer.Outcome.Ready ready,
                                    List<KaMoveNestedMemberComputer.TargetCandidate> candidates,
                                    KotlinMoveNestedMemberRefactoring refactoring) {
        this.ready = ready;
        this.candidates = candidates;
        this.refactoring = refactoring;
    }

    /** @return operation label shown by NetBeans. */
    @Override public String getName() { return "Move Nested Member"; }

    /** @return description naming the declaration selected at the editor caret. */
    @Override public String getDescription() { return "Move nested member '" + ready.getDeclarationName() + "'"; }

    /** @return {@code false}; this operation changes Kotlin source. */
    @Override public boolean isQuery() { return false; }

    /** @return the reusable passive target chooser. */
    @Override public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) panel = new TargetPanel(ready, candidates, parent);
        return panel;
    }

    /** Copies the selected target candidate to the controller carrier. */
    @Override public Problem setParameters() {
        if (panel != null) panel.copyTo(refactoring);
        return checkParameters();
    }

    /** @return fatal validation problem when no target was selected. */
    @Override public Problem checkParameters() {
        if (panel != null && panel.selected() == null) {
            return new Problem(true, "Select a target class or object.");
        }
        return null;
    }

    /** @return {@code true}; the user chooses a destination. */
    @Override public boolean hasParameters() { return true; }

    /** @return refactoring carrier accepted by the plugin factory. */
    @Override public AbstractRefactoring getRefactoring() { return refactoring; }

    /** @return no dedicated help page currently exists. */
    @Override public HelpCtx getHelpCtx() { return null; }

    /** Swing-only target chooser; deliberately contains no K2/project/session calls. */
    private static final class TargetPanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JComboBox<KaMoveNestedMemberComputer.TargetCandidate> targets;

        /** Creates the target chooser from immutable controller-supplied candidate data. */
        TargetPanel(KaMoveNestedMemberComputer.Outcome.Ready ready,
                    List<KaMoveNestedMemberComputer.TargetCandidate> candidates,
                    ChangeListener changeListener) {
            targets = new JComboBox<>(candidates.toArray(new KaMoveNestedMemberComputer.TargetCandidate[0]));
            targets.setRenderer(new TargetRenderer());
            targets.addActionListener(event -> changeListener.stateChanged(null));

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            GridBagConstraints label = new GridBagConstraints();
            label.anchor = GridBagConstraints.WEST;
            label.insets = new Insets(2, 0, 2, 8);
            GridBagConstraints field = new GridBagConstraints();
            field.fill = GridBagConstraints.HORIZONTAL;
            field.weightx = 1.0;
            field.gridwidth = GridBagConstraints.REMAINDER;
            field.insets = new Insets(2, 0, 2, 0);

            form.add(new JLabel("Member:"), label);
            form.add(new JLabel(ready.getDeclarationName()), field);
            JLabel targetLabel = new JLabel(ready.getRequiresCompanionTarget()
                    ? "Target class/object with companion:" : "Target class/object:");
            targetLabel.setLabelFor(targets);
            form.add(targetLabel, label);
            form.add(targets, field);
            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);
        }

        /** Copies only the selection; semantic validation is deferred to the plugin. */
        void copyTo(KotlinMoveNestedMemberRefactoring refactoring) {
            KaMoveNestedMemberComputer.TargetCandidate selected = selected();
            refactoring.setTargetFilePath(selected == null ? "" : selected.getFilePath());
            refactoring.setTargetOffset(selected == null ? -1 : selected.getOffset());
        }

        /** @return selected immutable target candidate, if any. */
        KaMoveNestedMemberComputer.TargetCandidate selected() {
            return (KaMoveNestedMemberComputer.TargetCandidate) targets.getSelectedItem();
        }

        /** Focuses the target selector when NetBeans opens the dialog. */
        @Override public void initialize() { targets.requestFocusInWindow(); }

        /** @return panel root component. */
        @Override public Component getComponent() { return component; }
    }

    /** Renders a target type with a compact, readable label. */
    private static final class TargetRenderer extends JLabel
            implements ListCellRenderer<KaMoveNestedMemberComputer.TargetCandidate> {
        @Override public Component getListCellRendererComponent(
                JList<? extends KaMoveNestedMemberComputer.TargetCandidate> list,
                KaMoveNestedMemberComputer.TargetCandidate value, int index, boolean selected, boolean focus) {
            setText(value == null ? "" : value.getPresentation());
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
