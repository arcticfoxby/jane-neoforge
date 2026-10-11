package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ManifestCodec;
import dev.modsbyfox.jane.neoforge.network.ResultPayload;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** A one-connection decision that can be reused only for the same complete required manifest. */
final class JaneLoginPreflightMemo {
    private final Object connection;
    private final byte[] manifestBytes;
    private final ResultPayload.Status status;

    JaneLoginPreflightMemo(Object connection, byte[] manifestBytes, ResultPayload.Status status) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.manifestBytes = Objects.requireNonNull(manifestBytes, "manifestBytes").clone();
        if (status != ResultPayload.Status.PASS && status != ResultPayload.Status.USER_OVERRIDE)
            throw new IllegalArgumentException("Only accepted login decisions can be reused");
        this.status = status;
    }

    static byte[] requiredBytes(ManifestCodec.LoginOffer offer) throws IOException {
        return ManifestCodec.encode(Objects.requireNonNull(offer, "offer").manifest());
    }

    boolean owns(Object connection) {
        return this.connection == connection;
    }

    ResultPayload.Status reuse(Object connection, byte[] requiredBytes) throws IOException {
        if (!owns(connection) || !Arrays.equals(manifestBytes, requiredBytes))
            throw new IOException("Jane login and configuration required manifests do not match");
        return status;
    }
}
