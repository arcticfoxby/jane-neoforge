package dev.modsbyfox.jane.core;

import java.util.List;
import java.util.Objects;

public record PendingSyncContext(String serverAddress, String serverId, RequiredManifest manifest,
                                 List<Comparison.Result> results, ServerProviderOffer provider) {
    public PendingSyncContext(String serverAddress, RequiredManifest manifest, List<Comparison.Result> results) {
        this(serverAddress, manifest, results, null);
    }

    public PendingSyncContext(String serverAddress, RequiredManifest manifest, List<Comparison.Result> results,
                              ServerProviderOffer provider) {
        this(ServerIdentity.normalize(serverAddress), ServerIdentity.id(serverAddress), manifest, results, provider);
    }

    public PendingSyncContext(String serverAddress, String serverId, RequiredManifest manifest, List<Comparison.Result> results) {
        this(serverAddress, serverId, manifest, results, null);
    }

    public PendingSyncContext {
        serverAddress = ServerIdentity.normalize(serverAddress);
        if (!ServerIdentity.id(serverAddress).equals(serverId)) {
            throw new IllegalArgumentException("Server ID does not match address");
        }
        manifest = Objects.requireNonNull(manifest, "manifest");
        results = List.copyOf(results);
    }
}
