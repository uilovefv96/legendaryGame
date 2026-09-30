package com.legendary.game.network.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legendary.protocol.LoginRequest;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class ProtocolCodecTest {
    @Test
    void encodesAndDecodesProtobufMessage() {
        EmbeddedChannel channel = new EmbeddedChannel(new GameMessageEncoder(), new GameMessageDecoder());
        LoginRequest request = LoginRequest.newBuilder()
                .setAccountName("player-001")
                .setClientVersion("0.1.0")
                .build();

        assertTrue(channel.writeOutbound(new GameMessage(MessageType.LOGIN_REQUEST, 7, request)));
        Object encoded = channel.readOutbound();
        assertTrue(encoded instanceof io.netty.buffer.ByteBuf);

        assertTrue(channel.writeInbound(encoded));
        GameMessage decoded = channel.readInbound();
        assertEquals(MessageType.LOGIN_REQUEST, decoded.type());
        assertEquals(7, decoded.requestId());
        assertEquals(request, decoded.body());
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsInvalidMagic() {
        EmbeddedChannel channel = new EmbeddedChannel(new GameMessageDecoder());
        var frame = Unpooled.buffer(FrameHeader.LENGTH);
        frame.writeShort(0x1234);
        frame.writeByte(FrameHeader.CURRENT_VERSION);
        frame.writeByte(0);
        frame.writeShort(MessageType.LOGIN_REQUEST.code());
        frame.writeInt(1);
        frame.writeInt(0);

        assertThrows(ProtocolException.class, () -> channel.writeInbound(frame));
        channel.finishAndReleaseAll();
    }
}
