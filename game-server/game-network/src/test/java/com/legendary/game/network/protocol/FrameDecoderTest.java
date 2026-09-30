package com.legendary.game.network.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legendary.protocol.LoginRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.junit.jupiter.api.Test;

class FrameDecoderTest {
    @Test
    void waitsForTheRestOfAHalfFrameBeforeDecoding() {
        EmbeddedChannel encoder = new EmbeddedChannel(new GameMessageEncoder());
        LoginRequest request = LoginRequest.newBuilder().setAccountName("half-frame").build();
        assertTrue(encoder.writeOutbound(new GameMessage(MessageType.LOGIN_REQUEST, 11, request)));
        ByteBuf encoded = encoder.readOutbound();
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.readBytes(bytes);
        encoded.release();
        encoder.finishAndReleaseAll();

        EmbeddedChannel decoder = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(1024 * 1024, 10, 4, 0, 0),
                new GameMessageDecoder());
        int split = 5;
        decoder.writeInbound(Unpooled.wrappedBuffer(bytes, 0, split));
        assertNull(decoder.readInbound());

        decoder.writeInbound(Unpooled.wrappedBuffer(bytes, split, bytes.length - split));
        GameMessage decoded = decoder.readInbound();
        assertEquals(MessageType.LOGIN_REQUEST, decoded.type());
        assertEquals("half-frame", ((LoginRequest) decoded.body()).getAccountName());
        decoder.finishAndReleaseAll();
    }
}
