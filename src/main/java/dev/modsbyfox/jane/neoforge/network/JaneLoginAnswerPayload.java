package dev.modsbyfox.jane.neoforge.network;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

/** The single-byte result of Jane's login preflight. The enclosing vanilla packet adds a nullable flag. */
public record JaneLoginAnswerPayload(ResultPayload.Status status) implements CustomQueryAnswerPayload {
    public JaneLoginAnswerPayload {
        Objects.requireNonNull(status, "status");
    }

    /** Decode the nullable flag written by the enclosing vanilla answer packet. */
    public static JaneLoginAnswerPayload readNullable(FriendlyByteBuf buffer) {
        if (!buffer.isReadable()) throw new IllegalArgumentException("Missing Jane login answer flag");
        int present = buffer.readUnsignedByte();
        if (present == 0) {
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing null Jane login answer bytes");
            return null;
        }
        if (present != 1) throw new IllegalArgumentException("Invalid Jane login answer flag");
        return read(buffer);
    }

    public static JaneLoginAnswerPayload read(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() != 1)
            throw new IllegalArgumentException("Invalid Jane login answer length");
        return new JaneLoginAnswerPayload(switch (buffer.readUnsignedByte()) {
            case 0 -> ResultPayload.Status.PASS;
            case 1 -> ResultPayload.Status.ACTION_REQUIRED;
            case 2 -> ResultPayload.Status.PROTOCOL_ERROR;
            case 3 -> ResultPayload.Status.USER_OVERRIDE;
            default -> throw new IllegalArgumentException("Invalid Jane login answer status");
        });
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeByte(switch (status) {
            case PASS -> 0;
            case ACTION_REQUIRED -> 1;
            case PROTOCOL_ERROR -> 2;
            case USER_OVERRIDE -> 3;
        });
    }
}
