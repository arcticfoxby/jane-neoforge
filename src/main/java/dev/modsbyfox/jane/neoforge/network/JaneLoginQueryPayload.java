package dev.modsbyfox.jane.neoforge.network;

import dev.modsbyfox.jane.core.RequiredManifest;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;

/** A bounded Jane offer carried by the original login connection, before NeoForge channel negotiation. */
public record JaneLoginQueryPayload(byte[] bytes) implements CustomQueryPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("jane", "login_preflight");
    public static final int TRANSACTION_ID = 0x4A414E45;
    public static final int MAX_MANIFEST_BYTES = RequiredManifest.MAX_PAYLOAD;

    public JaneLoginQueryPayload {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 8 || bytes.length > MAX_MANIFEST_BYTES)
            throw new IllegalArgumentException("Invalid Jane login manifest size");
        bytes = bytes.clone();
    }

    public static JaneLoginQueryPayload read(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 8 || length > MAX_MANIFEST_BYTES || length != buffer.readableBytes())
            throw new IllegalArgumentException("Invalid Jane login manifest frame");
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return new JaneLoginQueryPayload(bytes);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(bytes.length);
        buffer.writeBytes(bytes);
    }
}
