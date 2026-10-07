package dev.modsbyfox.jane.core;

import java.util.OptionalInt;

/** An ephemeral capability for files in one login's required manifest. */
public record ServerProviderOffer(ServerProviderTransport transport, String token, OptionalInt advertisedPort) {
    public ServerProviderOffer {
        if (transport == null || token == null || !token.matches("[0-9a-f]{64}") || advertisedPort == null
                || (transport == ServerProviderTransport.MINECRAFT && advertisedPort.isPresent())
                || (transport == ServerProviderTransport.SEPARATE_PORT &&
                (advertisedPort.isEmpty() || advertisedPort.getAsInt() < 1 || advertisedPort.getAsInt() > 65535))) {
            throw new IllegalArgumentException("Invalid ServerProvider offer");
        }
    }

    /** Compatibility constructor for the existing separate-port transport. */
    public ServerProviderOffer(int port, String token) {
        this(ServerProviderTransport.SEPARATE_PORT, token, OptionalInt.of(port));
    }

    public static ServerProviderOffer minecraft(String token) {
        return new ServerProviderOffer(ServerProviderTransport.MINECRAFT, token, OptionalInt.empty());
    }

    public static ServerProviderOffer separatePort(int port, String token) {
        return new ServerProviderOffer(ServerProviderTransport.SEPARATE_PORT, token, OptionalInt.of(port));
    }

    @Override public String toString() {
        return "ServerProviderOffer[transport=" + transport + ", advertisedPort=" + advertisedPort + ", token=<redacted>]";
    }
}
