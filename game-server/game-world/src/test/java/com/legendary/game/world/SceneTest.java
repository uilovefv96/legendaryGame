package com.legendary.game.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SceneTest {
    @Test
    void commandRunsOnSceneTickThread() throws Exception {
        Scene scene = new Scene();
        CountDownLatch executed = new CountDownLatch(1);
        try {
            scene.start();
            scene.submit(() -> {
                assertTrue(Thread.currentThread().getName().startsWith("scene-tick"));
                executed.countDown();
            });
            assertTrue(executed.await(1, TimeUnit.SECONDS));
        } finally {
            scene.close();
        }
    }
}
