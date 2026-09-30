package com.legendary.game.network.protocol;

/**
 * Legendary Online 第一版固定长度消息头。
 *
 * <p>消息头固定为 14 字节，并统一使用大端序。固定布局的好处是解码简单、跨语言
 * 实现容易对齐；代价是后续增加头字段需要升级协议版本或重新设计布局。</p>
 */
public record FrameHeader(
        int magic,
        int protocolVersion,
        int flags,
        int messageType,
        long requestId,
        int bodyLength) {

    public static final int LENGTH = 14;
    public static final int MAGIC = 0x4C47;
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_BODY_LENGTH = 1024 * 1024 - LENGTH;

    /** 创建当前版本的消息头。 */
    public static FrameHeader current(MessageType type, long requestId, int bodyLength) {
        return new FrameHeader(MAGIC, CURRENT_VERSION, 0, type.code(), requestId, bodyLength);
    }

    /** 验证传输层字段，业务字段由 Protobuf 和业务处理器继续校验。 */
    public void validate() {
        if (magic != MAGIC) {
            throw new ProtocolException("invalid magic: " + Integer.toHexString(magic));
        }
        if (protocolVersion != CURRENT_VERSION) {
            throw new ProtocolException("unsupported protocol version: " + protocolVersion);
        }
        if (bodyLength < 0 || bodyLength > MAX_BODY_LENGTH) {
            throw new ProtocolException("invalid body length: " + bodyLength);
        }
        if (requestId < 0 || requestId > 0xFFFFFFFFL) {
            throw new ProtocolException("request id does not fit uint32: " + requestId);
        }
        if (MessageType.fromCode(messageType) == null) {
            throw new ProtocolException("unknown message type: " + messageType);
        }
    }
}
