package com.legendary.game.network.session;

import io.netty.channel.Channel;

/**
 * 表示一个已经通过登录校验的客户端连接。
 *
 * <p>这里的 Session 不是 HTTP Session，而是游戏长连接会话：一条 TCP 连接在登录成功后，
 * 会绑定一个 {@code sessionId}、{@code accountId} 和 {@code playerId}。后续进入场景、移动、
 * 攻击等请求都应该先从 Channel 上取到当前 Session，确认“这个连接是谁”，再把操作意图
 * 投递给场景线程。</p>
 *
 * <p>当前阶段还没有数据库，所以账号和玩家 ID 由服务端临时生成。这样做的目的不是模拟真实
 * 账号系统，而是先把 Netty 长连接中的身份边界建立起来：未登录连接不能直接操作场景，断线时
 * 能根据 Session 清理在线状态。</p>
 */
public record ClientSession(
        String sessionId,
        String accountId,
        String playerId,
        Channel channel,
        long loginTimeMillis) {
}
