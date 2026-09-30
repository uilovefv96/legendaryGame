package com.legendary.game.network.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legendary.game.network.session.SessionRegistry;
import com.legendary.protocol.ErrorCode;
import com.legendary.protocol.HeartbeatRequest;
import com.legendary.protocol.HeartbeatResponse;
import com.legendary.protocol.LoginRequest;
import com.legendary.protocol.LoginResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class GameMessageDispatcherTest {
    @Test
    void loginRegistersSessionAndWritesLoginResponse() {
        SessionRegistry sessionRegistry = new SessionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(
                new GameMessageEncoder(),
                new GameMessageDispatcher(sessionRegistry));

        LoginRequest request = LoginRequest.newBuilder()
                .setAccountName("player-001")
                .setClientVersion("0.1.0")
                .build();

        channel.writeInbound(new GameMessage(MessageType.LOGIN_REQUEST, 101, request));

        GameMessage responseMessage = decodeOutbound(channel);
        assertEquals(MessageType.LOGIN_RESPONSE, responseMessage.type());
        assertEquals(101, responseMessage.requestId());

        LoginResponse response = assertInstanceOf(LoginResponse.class, responseMessage.body());
        assertEquals(ErrorCode.OK, response.getErrorCode());
        assertFalse(response.getSessionId().isBlank());
        assertFalse(response.getAccountId().isBlank());
        assertFalse(response.getPlayerId().isBlank());
        assertTrue(sessionRegistry.findByPlayerId(response.getPlayerId()).isPresent());
        assertTrue(sessionRegistry.findBySessionId(response.getSessionId()).isPresent());
        assertEquals(1, sessionRegistry.onlineCount());

        channel.finishAndReleaseAll();
        assertEquals(0, sessionRegistry.onlineCount());
    }

    @Test
    void heartbeatWritesServerTimeResponse() {
        SessionRegistry sessionRegistry = new SessionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(
                new GameMessageEncoder(),
                new GameMessageDispatcher(sessionRegistry));
        LoginRequest loginRequest = LoginRequest.newBuilder()
                .setAccountName("player-001")
                .setClientVersion("0.1.0")
                .build();
        channel.writeInbound(new GameMessage(MessageType.LOGIN_REQUEST, 101, loginRequest));
        decodeOutbound(channel);

        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setClientTimeMillis(123456L)
                .build();

        channel.writeInbound(new GameMessage(MessageType.HEARTBEAT_REQUEST, 102, request));

        GameMessage responseMessage = decodeOutbound(channel);
        assertEquals(MessageType.HEARTBEAT_RESPONSE, responseMessage.type());
        assertEquals(102, responseMessage.requestId());

        HeartbeatResponse response = assertInstanceOf(HeartbeatResponse.class, responseMessage.body());
        assertTrue(response.getServerTimeMillis() > 0);
        channel.finishAndReleaseAll();
    }

    private GameMessage decodeOutbound(EmbeddedChannel sourceChannel) {
        ByteBuf encoded = sourceChannel.readOutbound();
        EmbeddedChannel decoderChannel = new EmbeddedChannel(new GameMessageDecoder());
        assertTrue(decoderChannel.writeInbound(encoded));
        GameMessage message = decoderChannel.readInbound();
        decoderChannel.finishAndReleaseAll();
        return message;
    }
}
