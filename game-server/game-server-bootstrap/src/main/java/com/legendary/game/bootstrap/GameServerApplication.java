package com.legendary.game.bootstrap;

import com.legendary.game.network.GameServer;
import com.legendary.game.world.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 服务端进程入口，负责组装模块和优雅停机。 */
public final class GameServerApplication {
    private static final Logger log = LoggerFactory.getLogger(GameServerApplication.class);

    private GameServerApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("game.server.port", 9000);
        GameServer server = new GameServer(port);
        Scene scene = new Scene();
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
