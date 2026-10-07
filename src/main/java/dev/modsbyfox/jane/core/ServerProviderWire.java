package dev.modsbyfox.jane.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Fixed-size transfer framing; no paths, URLs, or variable-sized request payloads. */
public final class ServerProviderWire {
    public static final byte OK = 0, UNAUTHORIZED = 1, NOT_ALLOWED = 2, STALE = 3,
            BAD_REQUEST = 4, SERVER_ERROR = 5;
    private static final int MAGIC = 0x4a414e45;
    private static final int VERSION = 1;
    public static final int REQUEST_BYTES = 8 + 64 + 128;
    public record Request(String token, String sha512) { }

    private ServerProviderWire() { }

    public static void writeRequest(DataOutputStream out, String token, String hash) throws IOException {
        if (token == null || !token.matches("[0-9a-f]{64}") || hash == null || !hash.matches("[0-9a-f]{128}")) {
            throw new IOException("Invalid provider request");
        }
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.write(token.getBytes(StandardCharsets.US_ASCII));
        out.write(hash.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    public static Request readRequest(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Invalid provider protocol");
        byte[] token = new byte[64], hash = new byte[128];
        in.readFully(token);
        in.readFully(hash);
        String tokenValue = new String(token, StandardCharsets.US_ASCII);
        String hashValue = new String(hash, StandardCharsets.US_ASCII);
        if (!tokenValue.matches("[0-9a-f]{64}") || !hashValue.matches("[0-9a-f]{128}")) {
            throw new IOException("Invalid provider request fields");
        }
        return new Request(tokenValue, hashValue);
    }
}
