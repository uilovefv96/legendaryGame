package com.legendary.game.network.session;

import com.legendary.protocol.LoginRequest;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保存当前进程内在线 Session 的注册表。
 *
 * <p>Netty 的多个 Worker EventLoop 线程可能同时处理不同连接的登录和断线事件，因此这里使用
 * {@link ConcurrentHashMap}，不能使用普通 {@link java.util.HashMap}。否则高并发登录时可能出现
 * 数据结构损坏、丢失在线会话或断线清理不完整。</p>
 *
 * <p>第一版采用进程内内存表，适合单服学习阶段。后续如果要做多进程网关或跨服，需要把在线状态、
 * 路由信息和顶号策略重新设计，不能简单把这个类直接扩展成分布式 Session。</p>
 */
public final class SessionRegistry {
    /**
     * Netty Channel 上的属性键，用来把“这条 TCP 连接”与登录后的 Session 绑定起来。
     *
     * <p>业务处理器收到后续消息时，可以直接从 Channel 读取当前 Session，避免每条消息都从
     * request body 中相信客户端传来的 playerId。客户端提交的身份字段都不可信，服务端必须以
     * 连接上绑定的 Session 为准。</p>
     */
    private static final AttributeKey<ClientSession> SESSION_KEY = AttributeKey.valueOf("legendary.session");

    private final AtomicLong idSequence = new AtomicLong(1000);
    private final ConcurrentMap<String, ClientSession> sessionById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClientSession> playerById = new ConcurrentHashMap<>();

    /**
     * 为当前 Channel 创建并绑定登录 Session。
     *
     * <p>当前阶段只校验账号名非空。真实项目中这里不会直接信任客户端，而是要校验 token、账号状态、
     * 封禁状态和版本兼容性。我们先保留最小规则，是为了把网络链路跑通，同时明确后续可以替换的入口。</p>
     */
    public Optional<ClientSession> login(Channel channel, LoginRequest request) {
        String accountName = request.getAccountName().trim();
        if (accountName.isEmpty()) {
            return Optional.empty();
        }

        ClientSession existing = channel.attr(SESSION_KEY).get();
        if (existing != null) {
            return Optional.of(existing);
        }

        long sequence = idSequence.incrementAndGet();
        ClientSession session = new ClientSession(
                UUID.randomUUID().toString(),
                "account-" + sequence,
                "player-" + sequence,
                channel,
                System.currentTimeMillis());

        channel.attr(SESSION_KEY).set(session);
        sessionById.put(session.sessionId(), session);
        playerById.put(session.playerId(), session);
        return Optional.of(session);
    }

    /** 返回当前 Channel 已绑定的登录 Session；未登录连接返回空。 */
    public Optional<ClientSession> currentSession(Channel channel) {
        return Optional.ofNullable(channel.attr(SESSION_KEY).get());
    }

    /** 根据玩家 ID 查询在线 Session；后续场景广播和单人推送会用到这个索引。 */
    public Optional<ClientSession> findByPlayerId(String playerId) {
        return Optional.ofNullable(playerById.get(playerId));
    }

    /** 根据 Session ID 查询在线 Session；后续断线重连和调试接口会用到这个索引。 */
    public Optional<ClientSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(sessionById.get(sessionId));
    }

    /**
     * 连接断开时清理在线索引。
     *
     * <p>如果不清理，服务端会误以为玩家仍在线，后续可能出现重复登录被拒、广播发往失效 Channel、
     * 内存持续增长等问题。</p>
     */
    public void remove(Channel channel) {
        ClientSession session = channel.attr(SESSION_KEY).getAndSet(null);
        if (session == null) {
            return;
        }
        sessionById.remove(session.sessionId(), session);
        playerById.remove(session.playerId(), session);
    }

    /** 测试和监控可用的在线人数视图。 */
    public int onlineCount() {
        return sessionById.size();
    }
}
