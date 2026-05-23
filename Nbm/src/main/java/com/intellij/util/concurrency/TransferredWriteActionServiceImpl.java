package com.intellij.util.concurrency;

/**
 * Stub implementation of {@link TransferredWriteActionService} for standalone (non-IDE) mode.
 *
 * In a full IDE, this service transfers write-action locks across threads (EDT → background).
 * In the K2 standalone analysis session used by this plugin, write actions run on the calling
 * thread, so simply executing the runnable in place is correct.
 *
 * Registered as an application service in
 * {@link io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession#initApplicationEnvironment()}.
 */
public class TransferredWriteActionServiceImpl implements TransferredWriteActionService {
    @Override
    public void runOnEdtWithTransferredWriteActionAndWait(@org.jetbrains.annotations.NotNull Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException ignored) {
            // MockComponentManager.createListener() always throws UnsupportedOperationException
            // when the message bus tries to instantiate lazy listeners in standalone mode.
            // Swallowing it lets K2 analysis and PSI operations continue without propagating
            // this limitation to callers (e.g. LLFirDiagnosticVisitor).
        }
    }
}
