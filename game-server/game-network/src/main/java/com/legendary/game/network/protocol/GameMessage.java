package com.legendary.game.network.protocol;

import com.google.protobuf.MessageLite;

/**
 * 网络层已经完成协议头解析后的统一消息对象。
 *
 * <p>业务处理器不需要再次处理 ByteBuf，也不需要自己判断 messageType；它可以直接
 * 根据 {@link #type()} 进行路由，并从 {@link #body()} 获取对应的 Protobuf 对象。</p>
 */
public record GameMessage(MessageType type, long requestId, MessageLite body) {
}
