package dev.modsbyfox.jane.neoforge.client;

import dev.modsbyfox.jane.core.ManifestEntry;
import dev.modsbyfox.jane.core.MinecraftTransferMarker;
import dev.modsbyfox.jane.core.ServerIdentity;
import dev.modsbyfox.jane.core.ServerProviderClient;
import dev.modsbyfox.jane.core.ServerProviderOffer;
import dev.modsbyfox.jane.core.ServerProviderTransport;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/** A new marked 1.21.1 login on the same endpoint used by the selected server. */
public final class MinecraftProviderDownload {
    private MinecraftProviderDownload() { }

    public static void download(String logicalAddress, ServerProviderOffer offer, ManifestEntry entry, Path part,
                                BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        if (offer.transport() != ServerProviderTransport.MINECRAFT)
            throw new IOException("Incorrect Jane provider transport");
        if (Files.exists(part)) throw new IOException("Provider temporary file already exists");
        ServerIdentity.normalize(logicalAddress);
        ServerAddress logical = ServerAddress.parseString(logicalAddress);
        Optional<ResolvedServerAddress> resolved = ServerNameResolver.DEFAULT.resolveAddress(logical);
        InetSocketAddress endpoint = resolved.orElseThrow(
                () -> new IOException("Minecraft server address cannot be resolved")).asInetSocketAddress();
        Socket socket = new Socket();
        AtomicBoolean finished = new AtomicBoolean();
        Thread cancelMonitor = new Thread(() -> {
            while (!finished.get()) {
                if (cancelled.getAsBoolean()) {
                    try { socket.close(); } catch (IOException ignored) { }
                    return;
                }
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { return; }
            }
        }, "Jane Minecraft provider cancel");
        cancelMonitor.setDaemon(true);
        cancelMonitor.start();
        try (socket) {
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            socket.connect(endpoint, 12_000);
            socket.setSoTimeout(30_000);
            OutputStream output = socket.getOutputStream();
            ClientIntentionPacket intention = new ClientIntentionPacket(
                    SharedConstants.getCurrentVersion().getProtocolVersion(), logical.getHost(), logical.getPort(),
                    ClientIntent.LOGIN);
            writePacket(output, HandshakeProtocols.SERVERBOUND, intention);
            writePacket(output, LoginProtocols.SERVERBOUND,
                    new ServerboundHelloPacket(MinecraftTransferMarker.PROFILE_NAME,
                            MinecraftTransferMarker.PROFILE_ID));
            DataInputStream input = new DataInputStream(socket.getInputStream());
            if (input.readInt() != MinecraftTransferMarker.ACK_MAGIC
                    || input.readInt() != MinecraftTransferMarker.TRANSPORT_VERSION)
                throw new IOException("Jane Minecraft-port provider did not acknowledge transfer");
            if (cancelled.getAsBoolean()) throw new IOException("Sync cancelled");
            ServerProviderClient.transfer(input, output, offer, entry, part, cancelled, progress);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(part);
            throw failure;
        } finally {
            finished.set(true);
            cancelMonitor.interrupt();
        }
    }

    private static void writePacket(OutputStream output, ProtocolInfo<?> protocol, Packet<?> packet)
            throws IOException {
        FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // The mapped 1.21.1 protocol supplies packet IDs and codecs; no layout is hard-coded.
            @SuppressWarnings({"rawtypes", "unchecked"})
            ProtocolInfo rawProtocol = protocol;
            rawProtocol.codec().encode(payload, packet);
            frame.writeVarInt(payload.readableBytes());
            frame.writeBytes(payload);
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            output.write(bytes);
            output.flush();
        } catch (RuntimeException failure) {
            throw new IOException("Cannot encode Jane Minecraft transfer login", failure);
        } finally {
            frame.release();
            payload.release();
        }
    }
}
