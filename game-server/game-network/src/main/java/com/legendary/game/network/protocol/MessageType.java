package com.legendary.game.network.protocol;

import com.legendary.protocol.HeartbeatRequest;
import com.legendary.protocol.HeartbeatResponse;
import com.legendary.protocol.EnterSceneRequest;
import com.legendary.protocol.EnterSceneResponse;
import com.legendary.protocol.LoginRequest;
import com.legendary.protocol.LoginResponse;
import com.legendary.protocol.MoveRequest;
import com.legendary.protocol.PlayerPositionSnapshot;
import com.legendary.protocol.SceneSnapshot;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 应用层消息类型编号。
 *
 * <p>TCP 只能看到一串字节，Protobuf body 本身也不能告诉我们应该使用哪个具体
 * Java 消息类解析。因此协议头中的 {@code messageType} 需要在这里映射到对应的
 * Protobuf parser。编号一旦发布就不能复用，和 `.proto` 字段编号一样属于协议兼容
 * 约束的一部分。</p>
 */
public enum MessageType {
    LOGIN_REQUEST(1, LoginRequest.parser()),
    HEARTBEAT_REQUEST(2, HeartbeatRequest.parser()),
    MOVE_REQUEST(3, MoveRequest.parser()),
    LOGIN_RESPONSE(4, LoginResponse.parser()),
    HEARTBEAT_RESPONSE(5, HeartbeatResponse.parser()),
    ENTER_SCENE_REQUEST(6, EnterSceneRequest.parser()),
    ENTER_SCENE_RESPONSE(7, EnterSceneResponse.parser()),
    PLAYER_POSITION_SNAPSHOT(8, PlayerPositionSnapshot.parser()),
    SCENE_SNAPSHOT(9, SceneSnapshot.parser());

    private static final Map<Integer, MessageType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(MessageType::code, value -> value));

    private final int code;
    private final Parser<? extends MessageLite> parser;

    MessageType(int code, Parser<? extends MessageLite> parser) {
        this.code = code;
        this.parser = parser;
    }

    public int code() {
        return code;
    }

    /** 根据消息体字节解析成该类型的 Protobuf 对象。 */
    public MessageLite parse(byte[] body) throws Exception {
        return parser.parseFrom(body);
    }

    /** 返回协议头中的编号对应的消息类型；未知编号返回 null，由上层关闭连接。 */
    public static MessageType fromCode(int code) {
        return BY_CODE.get(code);
    }
}
