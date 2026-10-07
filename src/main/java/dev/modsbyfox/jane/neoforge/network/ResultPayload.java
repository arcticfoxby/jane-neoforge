package dev.modsbyfox.jane.neoforge.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The client's result for the server's required manifest. */
public record ResultPayload(Status status) implements CustomPacketPayload {
    public enum Status {
        PASS(0), ACTION_REQUIRED(1), PROTOCOL_ERROR(2);

        private final int wireValue;

        Status(int wireValue) {
            this.wireValue = wireValue;
        }

        private static Status fromWireValue(int value) {
            return switch (value) {
                case 0 -> PASS;
                case 1 -> ACTION_REQUIRED;
                default -> PROTOCOL_ERROR;
            };
        }
    }

    public static final Type<ResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("jane", "configuration_result"));

    public static final StreamCodec<FriendlyByteBuf, ResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ResultPayload decode(FriendlyByteBuf buffer) {
            return new ResultPayload(Status.fromWireValue(buffer.readUnsignedByte()));
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ResultPayload payload) {
            buffer.writeByte(payload.status.wireValue);
        }
    };

    public ResultPayload {
        Objects.requireNonNull(status, "status");
    }

    @Override
    public Type<ResultPayload> type() {
        return TYPE;
    }
}
