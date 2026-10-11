package dev.modsbyfox.jane.neoforge;

import dev.modsbyfox.jane.core.MinecraftTransferMarker;
import dev.modsbyfox.jane.core.RequiredManifest;
import dev.modsbyfox.jane.core.ServerProviderCore;
import dev.modsbyfox.jane.core.ServerProviderWire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serves token-scoped JAR bytes on a fresh, explicitly marked Minecraft login connection. */
final class MinecraftProviderTransport implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("jane");
    private static final int MAX_ACTIVE = 16;
    private final ServerProviderCore core;
    private final Semaphore activeSlots = new Semaphore(MAX_ACTIVE);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "Jane Minecraft transfer");
                thread.setDaemon(true);
                return thread;
            });
    private final Set<Channel> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MinecraftProviderTransport(ServerProviderCore core) { this.core = core; }

    String issue(RequiredManifest manifest) { return core.issue(manifest); }

    void takeOver(MinecraftServer server, Connection connection) {
        Channel channel = connection.channel();
        if (!server.isDedicatedServer() || channel == null || !channel.eventLoop().inEventLoop()) {
            if (channel != null) channel.close();
            return;
        }
        if (!activeSlots.tryAcquire()) {
            channel.close();
            return;
        }
        boolean registered = false;
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("packet_handler") != connection
                    || !(pipeline.get("splitter") instanceof Varint21FrameDecoder)
                    || !(pipeline.get("decoder") instanceof PacketDecoder)
                    || !(pipeline.get("prepender") instanceof Varint21LengthFieldPrepender)
                    || !(pipeline.get("encoder") instanceof PacketEncoder)) {
                throw new IOException("Unexpected Minecraft login pipeline");
            }
            channel.config().setAutoRead(false);
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, io.netty.channel.ChannelHandler> handler : pipeline) names.add(handler.getKey());
            for (String name : names) pipeline.remove(name);
            pipeline.addLast("jane_write_timeout", new WriteTimeoutHandler(30, TimeUnit.SECONDS));
            pipeline.addLast("jane_transfer", new RequestDecoder());
            // This synthetic transfer login must not be ticked or authenticated as a player.
            server.execute(() -> server.getConnection().getConnections().remove(connection));
            active.add(channel);
            channel.closeFuture().addListener(done -> {
                active.remove(channel);
                activeSlots.release();
            });
            registered = true;
            ByteBuf acknowledgement = Unpooled.buffer(8)
                    .writeInt(MinecraftTransferMarker.ACK_MAGIC)
                    .writeInt(MinecraftTransferMarker.TRANSPORT_VERSION);
            channel.writeAndFlush(acknowledgement).addListener(done -> {
                if (done.isSuccess()) channel.config().setAutoRead(true);
                else channel.close();
            });
            LOGGER.info("Jane Minecraft-port provider accepted a marked transfer connection");
        } catch (Exception exception) {
            LOGGER.warn("Jane Minecraft-port provider rejected takeover: {}", exception.getClass().getSimpleName());
            if (!registered) {
                active.remove(channel);
                activeSlots.release();
            }
            channel.close();
        }
    }

    private final class RequestDecoder extends ByteToMessageDecoder {
        private ScheduledFuture<?> timeout;
        private boolean requested;

        @Override public void handlerAdded(ChannelHandlerContext context) throws Exception {
            super.handlerAdded(context);
            timeout = context.executor().schedule(() -> {
                if (!requested) context.close();
            }, 30L, TimeUnit.SECONDS);
        }

        @Override protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> out) {
            if (requested || input.readableBytes() > ServerProviderWire.REQUEST_BYTES) {
                context.close();
                return;
            }
            if (input.readableBytes() < ServerProviderWire.REQUEST_BYTES) return;
            byte[] bytes = new byte[ServerProviderWire.REQUEST_BYTES];
            input.readBytes(bytes);
            ServerProviderWire.Request request;
            try {
                request = ServerProviderWire.readRequest(new DataInputStream(new ByteArrayInputStream(bytes)));
            } catch (IOException exception) {
                context.writeAndFlush(Unpooled.buffer(1).writeByte(ServerProviderWire.BAD_REQUEST))
                        .addListener(done -> context.close());
                return;
            }
            requested = true;
            timeout.cancel(false);
            context.channel().config().setAutoRead(false);
            try {
                workers.execute(() -> serve(context.channel(), request));
            } catch (RejectedExecutionException exception) {
                context.close();
            }
        }

        @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
            if (timeout != null) timeout.cancel(false);
            super.channelInactive(context);
        }
    }

    private void serve(Channel channel, ServerProviderWire.Request request) {
        try {
            ServerProviderCore.Access access = core.open(request.token(), request.sha512());
            if (access.status() != ServerProviderWire.OK) {
                write(channel, Unpooled.buffer(1).writeByte(access.status()));
                return;
            }
            try (ServerProviderCore.Lease lease = access.lease()) {
                write(channel, Unpooled.buffer(9).writeByte(ServerProviderWire.OK)
                        .writeLong(lease.entry().fileSize()));
                byte[] buffer = new byte[64 * 1024];
                var stream = Channels.newInputStream(lease.channel());
                int count;
                while (channel.isActive() && (count = stream.read(buffer)) != -1) {
                    write(channel, Unpooled.wrappedBuffer(java.util.Arrays.copyOf(buffer, count)));
                }
                LOGGER.info("Jane Minecraft-port provider served modId={} hash={} bytes={}",
                        lease.entry().modId(), lease.entry().sha512().substring(0, 12), lease.entry().fileSize());
            }
        } catch (Exception exception) {
            LOGGER.warn("Jane Minecraft-port transfer failed: {}", exception.getClass().getSimpleName());
        } finally {
            channel.close();
        }
    }

    private static void write(Channel channel, ByteBuf bytes) throws IOException, InterruptedException {
        ChannelFuture sent = channel.writeAndFlush(bytes);
        sent.sync();
        if (!sent.isSuccess()) throw new IOException("Jane transfer write failed", sent.cause());
    }

    @Override public void close() {
        for (Channel channel : active) channel.close();
        workers.shutdownNow();
        core.close();
    }
}
