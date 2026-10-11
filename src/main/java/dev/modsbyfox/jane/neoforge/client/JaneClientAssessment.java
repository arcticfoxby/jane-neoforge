package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only configuration evaluator; UI and reconnect state live in the client lifecycle. */
public final class JaneClientAssessment {
    private JaneClientAssessment() { }

    public static CompletionStage<ResultPayload.Status> evaluate(ManifestCodec.LoginOffer offer,
            IPayloadContext context) throws IOException {
        return JaneClientLifecycle.evaluate(offer, context);
    }

    /** Retained for callers that only need the pending required-file decision. */
    public static PendingSyncContext takePending() {
        return JaneClientLifecycle.takePending();
    }
}
