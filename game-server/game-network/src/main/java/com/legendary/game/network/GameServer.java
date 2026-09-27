package com.legendary.game.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty TCP 服务端骨架。
 *
 * <p>这个类负责“监听端口”和“管理网络资源”，暂时不负责登录、移动和战斗。
 * 这是一个重要的职责边界：Netty 的 EventLoop 线程需要尽快返回，不能在其中执行
 * 复杂或可能阻塞的游戏逻辑，否则一个连接的慢操作可能拖慢同一个 EventLoop 上的
 * 其他连接。真正进入场景的操作，后续会转换成命令投递给 {@code Scene}。</p>
 *
 * <p>启动过程可以拆成三步理解：创建线程组、配置 {@link ServerBootstrap}、绑定端口。
 * 关闭过程则反过来释放监听 Channel 和两个 EventLoopGroup。</p>
 */
public final class GameServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GameServer.class);

    /** 服务端监听端口，例如本地开发默认使用 9000。 */
    private final int port;
    /**
     * Boss 线程组，负责接收新的 TCP 连接。
     *
     * <p>它通常不处理具体业务数据；连接建立后，会把连接注册到 Worker 线程组。</p>
     */
    private EventLoopGroup bossGroup;
    /**
     * Worker 线程组，负责已建立连接的读写事件和 Pipeline 处理。
     *
     * <p>这里的线程仍然是网络线程，不应直接修改场景状态。</p>
     */
    private EventLoopGroup workerGroup;
    /** 监听端口成功后得到的服务端 Channel，用于等待关闭和主动关闭。 */
    private Channel serverChannel;

    /** 创建服务端配置对象；真正创建线程组和绑定端口发生在 {@link #start()}。 */
    public GameServer(int port) {
        this.port = port;
    }

    /**
     * 启动 Netty 服务端。
     *
     * <p>{@code NioEventLoopGroup(1)} 表示当前骨架只使用一个 Boss 线程；Worker
     * 使用 Netty 默认线程数。{@code NioServerSocketChannel} 表示服务端使用 NIO
     * ServerSocketChannel 接收连接。{@code childHandler} 配置的是“每一条客户端连接”
     * 的 Pipeline，而不是服务端监听 Channel 的 Pipeline。</p>
     *
     * <p>{@code bind(port).sync()} 会等待端口绑定完成。只有绑定成功后，服务端才算真正
     * 启动；如果端口被占用，这里会抛出异常，而不是打印启动成功日志。</p>
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                // 第一个 group 处理新连接，第二个 group 处理已建立连接的读写事件。
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // 为每条客户端连接创建独立的 ChannelPipeline。
                .childHandler(new GameChannelInitializer())
                // TCP_NODELAY 禁用 Nagle 算法，减少小消息等待合并的延迟。
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 保持 TCP 层的连接存活探测；应用层心跳仍然需要后续单独实现。
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("Netty listening on port {}", port);
    }

    /**
     * 阻塞等待监听 Channel 关闭。
     *
     * <p>主线程在这里等待服务端运行；网络事件由 Netty 的 EventLoop 线程处理。
     * 当收到进程关闭信号或主动关闭 Channel 后，等待结束，调用方再执行资源释放。</p>
     */
    public void awaitClose() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    /**
     * 释放网络资源。
     *
     * <p>关闭方法设计成幂等风格：没有启动成功的资源直接跳过，已经关闭的资源不会
     * 再被重复使用。{@code shutdownGracefully()} 会让 Netty 尽量处理完已经提交的
     * 任务，再停止 EventLoop，而不是立即粗暴中断线程。</p>
     */
    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
