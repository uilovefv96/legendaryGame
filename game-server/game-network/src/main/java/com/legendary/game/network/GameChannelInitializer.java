package com.legendary.game.network;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/** 第一阶段只安装分帧和连接日志，消息编解码将在协议阶段接入。 */
final class GameChannelInitializer extends ChannelInitializer<SocketChannel> {
    private static final int MAX_FRAME_LENGTH = 1024 * 1024;

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new LoggingHandler(LogLevel.DEBUG));
        pipeline.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 10, 4, 0, 0));
    }
}
