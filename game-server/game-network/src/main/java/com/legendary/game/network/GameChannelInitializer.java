package com.legendary.game.network;

import com.legendary.game.network.protocol.GameMessageDispatcher;
import com.legendary.game.network.protocol.GameMessageDecoder;
import com.legendary.game.network.protocol.GameMessageEncoder;
import com.legendary.game.network.session.SessionRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * 为每条客户端 TCP 连接初始化 {@link ChannelPipeline}。
 *
 * <p>Netty 会在连接建立时调用 {@link #initChannel(SocketChannel)}。可以把 Pipeline
 * 理解成一条处理链：入站字节从前往后经过解码器，出站消息按相反方向经过编码器。
 * 后续会在这里加入自定义消息头解码、Protobuf 解码、消息分发和连接生命周期处理器。</p>
 *
 * <p>当前阶段已经接入消息头、Protobuf 编解码和轻量消息分发。注意这里仍然不是场景逻辑入口；
 * 登录和心跳可以在网络线程快速完成，移动和战斗后续必须投递给 Scene 命令队列。</p>
 */
final class GameChannelInitializer extends ChannelInitializer<SocketChannel> {
    /** 单帧最大长度，防止客户端通过超大长度字段消耗服务端内存。 */
    private static final int MAX_FRAME_LENGTH = 1024 * 1024;

    private final SessionRegistry sessionRegistry;

    GameChannelInitializer(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 配置当前客户端连接的处理链。
     *
     * <p>{@code LengthFieldBasedFrameDecoder} 的参数含义如下：</p>
     * <ul>
     *     <li>{@code MAX_FRAME_LENGTH}：允许的最大完整帧长度。</li>
     *     <li>{@code 10}：长度字段在帧中的起始偏移；第一版消息头中 bodyLength 位于第 10 字节。</li>
     *     <li>{@code 4}：长度字段占 4 字节。</li>
     *     <li>{@code 0}：长度字段表示的长度需要加上的修正值。</li>
     *     <li>{@code 0}：解码后丢弃的头部字节数；当前保留整个帧，方便下一阶段读取自定义消息头。</li>
     * </ul>
     *
     * <p>TCP 没有消息边界，一次 read 可能得到半个包，也可能得到多个包。这个解码器
     * 解决的是拆包和粘包造成的帧边界问题，不负责判断 messageType，也不负责解析 Protobuf。</p>
     */
    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new LoggingHandler(LogLevel.DEBUG));
        pipeline.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 10, 4, 0, 0));
        // 前一个处理器只负责恢复完整帧；这里负责读取消息头并解析 Protobuf body。
        pipeline.addLast(new GameMessageDecoder());
        // 出站时将 GameMessage 编码为“14 字节 header + Protobuf body”。
        pipeline.addLast(new GameMessageEncoder());
        // 入站 GameMessage 的轻量业务分发入口；复杂场景逻辑后续会投递到 Scene 命令队列。
        pipeline.addLast(new GameMessageDispatcher(sessionRegistry));
    }
}
