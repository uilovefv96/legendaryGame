package com.legendary.game.network.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * 将一帧完整 ByteBuf 解码为 {@link GameMessage}。
 *
 * <p>本处理器假设前一个 {@code LengthFieldBasedFrameDecoder} 已经保证了帧完整性，
 * 因此这里不再等待更多字节，只负责读取 14 字节 header、校验传输字段、查找消息类型
 * 并解析 Protobuf body。分层后，半包粘包问题和业务消息解析问题不会混在一起。</p>
 */
public final class GameMessageDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (input.readableBytes() < FrameHeader.LENGTH) {
            throw new ProtocolException("frame is shorter than header");
        }

        FrameHeader header = new FrameHeader(
                input.readUnsignedShort(),
                input.readUnsignedByte(),
                input.readUnsignedByte(),
                input.readUnsignedShort(),
                input.readUnsignedInt(),
                input.readInt());
        header.validate();

        if (input.readableBytes() != header.bodyLength()) {
            throw new ProtocolException("body length does not match frame: expected "
                    + header.bodyLength() + ", actual " + input.readableBytes());
        }

        byte[] bodyBytes = new byte[header.bodyLength()];
        input.readBytes(bodyBytes);
        MessageType type = MessageType.fromCode(header.messageType());
        try {
            output.add(new GameMessage(type, header.requestId(), type.parse(bodyBytes)));
        } catch (InvalidProtocolBufferException exception) {
            throw new ProtocolException("invalid protobuf body for " + type + ": " + exception.getMessage());
        } catch (Exception exception) {
            throw new ProtocolException("cannot parse message " + type + ": " + exception.getMessage());
        }
    }
}
