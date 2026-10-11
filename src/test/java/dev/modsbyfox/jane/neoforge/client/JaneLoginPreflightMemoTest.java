package dev.modsbyfox.jane.neoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class JaneLoginPreflightMemoTest {
    private static final RequiredManifest REQUIRED = new RequiredManifest(RequiredManifest.PROTOCOL,
            List.of(new ManifestEntry("example", "Example", "1.0", 42, "a".repeat(128))));

    @Test
    void sameConnectionAndManifestReuseDecisionAcrossProviderTokenRotation() throws IOException {
        Object connection = new Object();
        var login = offer(REQUIRED, "a".repeat(64));
        var configuration = offer(REQUIRED, "b".repeat(64));
        var memo = new JaneLoginPreflightMemo(connection, JaneLoginPreflightMemo.requiredBytes(login),
                ResultPayload.Status.USER_OVERRIDE);

        assertEquals(ResultPayload.Status.USER_OVERRIDE,
                memo.reuse(connection, JaneLoginPreflightMemo.requiredBytes(configuration)));
        assertThrows(IOException.class,
                () -> memo.reuse(new Object(), JaneLoginPreflightMemo.requiredBytes(configuration)));
    }

    @Test
    void changedRequiredHashRejectsConfigurationEvenWithSameConnection() throws IOException {
        Object connection = new Object();
        var memo = new JaneLoginPreflightMemo(connection,
                JaneLoginPreflightMemo.requiredBytes(offer(REQUIRED, "a".repeat(64))),
                ResultPayload.Status.PASS);
        var changed = new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("example", "Example", "1.0", 42, "b".repeat(128))));

        assertThrows(IOException.class,
                () -> memo.reuse(connection, JaneLoginPreflightMemo.requiredBytes(offer(changed, "b".repeat(64)))));
    }

    @Test
    void duplicateLoginQueryCancelsTheOnlyNormalReply() {
        var gate = new JaneLoginPreflightGate();
        assertTrue(gate.begin());
        assertFalse(gate.begin());
        assertTrue(gate.fail());
        assertFalse(gate.finish());
        assertFalse(gate.fail());

        var afterAnswer = new JaneLoginPreflightGate();
        assertTrue(afterAnswer.begin());
        assertTrue(afterAnswer.finish());
        assertFalse(afterAnswer.begin());
        assertTrue(afterAnswer.fail());
    }

    private static ManifestCodec.LoginOffer offer(RequiredManifest manifest, String token) {
        return new ManifestCodec.LoginOffer(manifest, ServerProviderOffer.minecraft(token));
    }
}
