package com.legendary.game.bot;

import com.legendary.game.network.protocol.GameMessage;
import com.legendary.game.network.protocol.GameMessageDecoder;
import com.legendary.game.network.protocol.GameMessageEncoder;
import com.legendary.game.network.protocol.MessageType;
import com.legendary.protocol.ErrorCode;
import com.legendary.protocol.HeartbeatRequest;
import com.legendary.protocol.HeartbeatResponse;
import com.legendary.protocol.LoginRequest;
import com.legendary.protocol.LoginResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java Bot 入口，用于验证真实 TCP 链路上的协议和服务端行为。
 *
 * <p>单元测试可以证明编解码器和 Handler 的局部行为，但不能证明服务端进程真的能被外部客户端连接。
 * Bot 的价值是作为“可重复执行的联调客户端”：它和未来 Unity 客户端一样走 TCP、消息头和 Protobuf，
 * 因此能更早暴露端口、Pipeline 顺序、协议类型映射和服务端响应等集成问题。</p>
 */
public final class GameBotApplication {
    private static final Logger log = LoggerFactory.getLogger(GameBotApplication.class);

    private GameBotApplication() {
    }

    /**
     * 连接服务端并执行“登录 -> 心跳 -> 关闭”的最小联调流程。
     *
     * <p>运行前需要先启动服务端。参数通过 JVM 系统属性控制，例如：
     * {@code -Dgame.bot.host=127.0.0.1 -Dgame.bot.port=9000 -Dgame.bot.account=bot-001}。</p>
     */
    public static void main(String[] args) throws Exception {
        String host = System.getProperty("game.bot.host", "127.0.0.1");
        int port = Integer.getInteger("game.bot.port", 9000);
        String accountName = System.getProperty("game.bot.account", "bot-001");

        CompletableFuture<Void> finished = new CompletableFuture<>();
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new BotChannelInitializer(accountName, finished));

            log.info("Bot connecting to {}:{} as {}", host, port, accountName);
            ChannelFuture connectFuture = bootstrap.connect(host, port).sync();
            finished.get(10, TimeUnit.SECONDS);
            connectFuture.channel().closeFuture().sync();
            log.info("Bot finished login and heartbeat check");
        } finally {
            workerGroup.shutdownGracefully().sync();
        }
    }

    /** 为 Bot 的客户端连接安装与服务端兼容的协议处理链。 */
    private static final class BotChannelInitializer extends ChannelInitializer<SocketChannel> {
        private static final int MAX_FRAME_LENGTH = 1024 * 1024;

        private final String accountName;
        private final CompletableFuture<Void> finished;

        private BotChannelInitializer(String accountName, CompletableFuture<Void> finished) {
            this.accountName = accountName;
            this.finished = finished;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            ChannelPipeline pipeline = channel.pipeline();
            // 和服务端保持相同分帧参数，确保 Bot 不是绕过协议的特殊测试工具。
            pipeline.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 10, 4, 0, 0));
            pipeline.addLast(new GameMessageDecoder());
            pipeline.addLast(new GameMessageEncoder());
            pipeline.addLast(new BotClientHandler(accountName, finished));
        }
    }

    /**
     * Bot 的最小业务处理器。
     *
     * <p>这里仍然运行在 Netty 客户端 EventLoop 线程上，只做轻量状态推进。未来批量压测时，
     * 不应在这里写阻塞等待、文件 IO 或复杂统计聚合，否则会影响 Bot 自身对延迟的测量。</p>
     */
    private static final class BotClientHandler extends SimpleChannelInboundHandler<GameMessage> {
        private final AtomicLong requestSequence = new AtomicLong(1);
        private final String accountName;
        private final CompletableFuture<Void> finished;

        private BotClientHandler(String accountName, CompletableFuture<Void> finished) {
            this.accountName = accountName;
            this.finished = finished;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            LoginRequest request = LoginRequest.newBuilder()
                    .setAccountName(accountName)
                    .setClientVersion("0.1.0")
                    .build();
            long requestId = requestSequence.getAndIncrement();
            log.info("Bot sending LoginRequest requestId={}", requestId);
            context.writeAndFlush(new GameMessage(MessageType.LOGIN_REQUEST, requestId, request));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, GameMessage message) {
            switch (message.type()) {
                case LOGIN_RESPONSE -> handleLoginResponse(context, message);
                case HEARTBEAT_RESPONSE -> handleHeartbeatResponse(context, message);
                default -> throw new IllegalStateException("unexpected bot response: " + message.type());
            }
        }

        private void handleLoginResponse(ChannelHandlerContext context, GameMessage message) {
            LoginResponse response = (LoginResponse) message.body();
            if (response.getErrorCode() != ErrorCode.OK) {
                throw new IllegalStateException("login failed: " + response.getErrorCode());
            }

            log.info("Bot login success sessionId={} playerId={}", response.getSessionId(), response.getPlayerId());
            HeartbeatRequest heartbeat = HeartbeatRequest.newBuilder()
                    .setClientTimeMillis(System.currentTimeMillis())
                    .build();
            long requestId = requestSequence.getAndIncrement();
            log.info("Bot sending HeartbeatRequest requestId={}", requestId);
            context.writeAndFlush(new GameMessage(MessageType.HEARTBEAT_REQUEST, requestId, heartbeat));
        }

        private void handleHeartbeatResponse(ChannelHandlerContext context, GameMessage message) {
            HeartbeatResponse response = (HeartbeatResponse) message.body();
            log.info("Bot heartbeat response serverTimeMillis={}", response.getServerTimeMillis());
            finished.complete(null);
            context.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            finished.completeExceptionally(cause);
            context.close();
        }
    }
}
