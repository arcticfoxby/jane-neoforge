package dev.modsbyfox.jane.neoforge.network;

import dev.modsbyfox.jane.core.RequiredManifest;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The bounded Protocol 3 bytes produced by {@code ManifestCodec}. */
public record ManifestPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<ManifestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("jane", "required_manifest"));

    public static final StreamCodec<FriendlyByteBuf, ManifestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ManifestPayload decode(FriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > RequiredManifest.MAX_PAYLOAD || size > buffer.readableBytes()) {
                throw new IllegalArgumentException("Invalid Jane manifest payload size");
            }
            byte[] bytes = new byte[size];
            buffer.readBytes(bytes);
            return new ManifestPayload(bytes);
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ManifestPayload payload) {
            buffer.writeVarInt(payload.bytes.length);
            buffer.writeBytes(payload.bytes);
        }
    };

    public ManifestPayload {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > RequiredManifest.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Jane manifest payload exceeds the Protocol 3 limit");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public Type<ManifestPayload> type() {
        return TYPE;
    }
}
