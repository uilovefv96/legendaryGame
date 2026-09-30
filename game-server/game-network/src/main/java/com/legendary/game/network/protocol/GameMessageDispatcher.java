package com.legendary.game.network.protocol;

import com.legendary.game.network.session.ClientSession;
import com.legendary.game.network.session.SessionRegistry;
import com.legendary.protocol.ErrorCode;
import com.legendary.protocol.HeartbeatResponse;
import com.legendary.protocol.LoginRequest;
import com.legendary.protocol.LoginResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 网络层第一版消息分发器。
 *
 * <p>前面的 {@link GameMessageDecoder} 已经把 TCP 字节流解析成 {@link GameMessage}，这里才开始
 * 关注业务消息类型。它仍然运行在 Netty EventLoop 线程上，所以只能处理非常轻量的逻辑：登录注册、
 * 心跳响应、基础鉴权判断。耗时 IO、数据库查询、复杂战斗计算和场景状态修改都不应该直接放在这里。</p>
 *
 * <p>后续进入场景、移动和攻击会从这里转换成命令对象，投递给 Scene 的命令队列，由场景 Tick 线程
 * 顺序处理。这样可以避免多个 Netty 线程同时修改同一个地图里的玩家和怪物状态。</p>
 */
public final class GameMessageDispatcher extends SimpleChannelInboundHandler<GameMessage> {
    private final SessionRegistry sessionRegistry;

    public GameMessageDispatcher(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, GameMessage message) {
        switch (message.type()) {
            case LOGIN_REQUEST -> handleLogin(context, message);
            case HEARTBEAT_REQUEST -> handleHeartbeat(context, message);
            default -> context.close();
        }
    }

    private void handleLogin(ChannelHandlerContext context, GameMessage message) {
        LoginRequest request = (LoginRequest) message.body();
        LoginResponse response = sessionRegistry.login(context.channel(), request)
                .map(this::successLoginResponse)
                .orElseGet(() -> LoginResponse.newBuilder()
                        .setErrorCode(ErrorCode.INVALID_REQUEST)
                        .build());

        context.writeAndFlush(new GameMessage(MessageType.LOGIN_RESPONSE, message.requestId(), response));
    }

    private LoginResponse successLoginResponse(ClientSession session) {
        return LoginResponse.newBuilder()
                .setErrorCode(ErrorCode.OK)
                .setAccountId(session.accountId())
                .setPlayerId(session.playerId())
                .setSessionId(session.sessionId())
                .build();
    }

    private void handleHeartbeat(ChannelHandlerContext context, GameMessage message) {
        if (sessionRegistry.currentSession(context.channel()).isEmpty()) {
            context.close();
            return;
        }

        HeartbeatResponse response = HeartbeatResponse.newBuilder()
                .setServerTimeMillis(System.currentTimeMillis())
                .build();
        context.writeAndFlush(new GameMessage(MessageType.HEARTBEAT_RESPONSE, message.requestId(), response));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        sessionRegistry.remove(context.channel());
        super.channelInactive(context);
    }
}
