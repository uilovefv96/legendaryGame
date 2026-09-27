package com.legendary.game.bootstrap;

import com.legendary.game.network.GameServer;
import com.legendary.game.world.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端进程入口，负责组装模块和优雅停机。
 *
 * <p>这个类类似 Spring Boot 应用中的启动类，但当前故意不引入 Spring 容器。这样可以
 * 直接看到游戏服务端的模块组装、线程启动和关闭顺序。后续如果增加 Spring Boot 管理
 * 接口，它也只作为外围模块，不负责每次移动和战斗请求。</p>
 */
public final class GameServerApplication {
    private static final Logger log = LoggerFactory.getLogger(GameServerApplication.class);

    /** 防止被当作工具类实例化。 */
    private GameServerApplication() {
    }

    /**
     * 启动场景 Tick 和 Netty 网络服务。
     *
     * <p>端口通过 JVM 系统参数读取，例如 {@code -Dgame.server.port=9001}；没有配置时
     * 使用 9000。关闭钩子由 JVM 在正常退出时调用，用于停止场景线程和 Netty 线程组。</p>
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("game.server.port", 9000);
        GameServer server = new GameServer(port);
        Scene scene = new Scene();
        // 关闭顺序：先停止场景命令处理，再释放网络资源，避免新请求继续进入已关闭场景。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scene.close();
            server.close();
        }, "game-server-shutdown"));

        scene.start();
        server.start();
        log.info("Game server started");
        server.awaitClose();
    }
}
