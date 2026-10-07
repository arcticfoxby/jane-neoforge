package dev.modsbyfox.jane.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class ManifestCodec {
    public record LoginOffer(RequiredManifest manifest, ServerProviderOffer provider) { }
    private ManifestCodec() { }

    public static byte[] encode(RequiredManifest manifest) throws IOException {
        return encode(new LoginOffer(manifest, null));
    }

    public static byte[] encode(LoginOffer offer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        RequiredManifest manifest = offer.manifest();
        out.writeInt(manifest.protocol());
        out.writeInt(manifest.entries().size());
        for (ManifestEntry entry : manifest.entries()) {
            writeString(out, entry.modId());
            writeString(out, entry.displayName());
            writeString(out, entry.version());
            out.writeLong(entry.fileSize());
            writeString(out, entry.sha512());
        }
        out.writeBoolean(offer.provider() != null);
        if (offer.provider() != null) {
            out.writeByte(offer.provider().transport() == ServerProviderTransport.MINECRAFT ? 1 : 2);
            writeString(out, offer.provider().token());
            if (offer.provider().transport() == ServerProviderTransport.SEPARATE_PORT)
                out.writeInt(offer.provider().advertisedPort().orElseThrow());
        }
        if (bytes.size() > RequiredManifest.MAX_PAYLOAD) throw new IOException("Manifest too large");
        return bytes.toByteArray();
    }

    public static RequiredManifest decode(byte[] bytes) throws IOException {
        return decodeOffer(bytes).manifest();
    }

    public static LoginOffer decodeOffer(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > RequiredManifest.MAX_PAYLOAD || bytes.length < 8) {
            throw new IOException("Invalid manifest payload size");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int protocol = in.readInt();
        if (protocol != RequiredManifest.PROTOCOL) throw new UnsupportedProtocolException(protocol);
        int count = in.readInt();
        if (count < 0 || count > RequiredManifest.MAX_ENTRIES) throw new IOException("Invalid entry count");
        ArrayList<ManifestEntry> entries = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                String id = readString(in, 64);
                String name = readString(in, 128 * 4);
                String version = readString(in, 64 * 4);
                long size = in.readLong();
                String hash = readString(in, 128);
                entries.add(new ManifestEntry(id, name, version, size, hash));
            }
            int flag = in.readUnsignedByte();
            if (flag != 0 && flag != 1) throw new IOException("Invalid provider flag");
            boolean available = flag == 1;
            ServerProviderOffer provider = null;
            if (available) {
                int transport = in.readUnsignedByte();
                if (transport != 1 && transport != 2) throw new IOException("Unknown ServerProvider transport");
                String token = readString(in, 64);
                provider = transport == 1 ? ServerProviderOffer.minecraft(token)
                        : ServerProviderOffer.separatePort(in.readInt(), token);
            }
            if (in.available() != 0) throw new IOException("Trailing manifest bytes");
            return new LoginOffer(new RequiredManifest(protocol, entries), provider);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid manifest entry", exception);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > maxBytes || length > in.available()) throw new IOException("Invalid string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8", exception);
        }
    }

    public static final class UnsupportedProtocolException extends IOException {
        public UnsupportedProtocolException(int version) {
            super("Unsupported Jane protocol: " + version);
        }
    }
}
