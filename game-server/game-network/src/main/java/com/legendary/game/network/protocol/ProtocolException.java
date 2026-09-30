package com.legendary.game.network.protocol;

/** 协议头或消息类型不符合约定时抛出的运行时异常。 */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
