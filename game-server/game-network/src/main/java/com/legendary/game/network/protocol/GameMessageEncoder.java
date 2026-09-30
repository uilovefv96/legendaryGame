package com.legendary.game.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 将统一消息对象编码为“14 字节消息头 + Protobuf body”。
 *
 * <p>出站编码器集中负责协议布局，业务代码只需要构造 {@link GameMessage}。这样可以
 * 避免登录、心跳和移动处理器分别手写 ByteBuf 偏移，减少跨语言协议不一致的风险。</p>
 */
public final class GameMessageEncoder extends MessageToByteEncoder<GameMessage> {
    @Override
    protected void encode(ChannelHandlerContext context, GameMessage message, ByteBuf output) {
        byte[] body = message.body().toByteArray();
        FrameHeader header = FrameHeader.current(message.type(), message.requestId(), body.length);
        header.validate();

        output.writeShort(header.magic());
        output.writeByte(header.protocolVersion());
        output.writeByte(header.flags());
        output.writeShort(header.messageType());
        output.writeInt((int) header.requestId());
        output.writeInt(header.bodyLength());
        output.writeBytes(body);
    }
}
