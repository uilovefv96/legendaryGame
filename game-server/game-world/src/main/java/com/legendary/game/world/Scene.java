package com.legendary.game.world;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 场景状态只允许在 Tick 线程内修改。 */
public final class Scene implements AutoCloseable {
    private final BlockingQueue<Runnable> commandQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "scene-tick");
        thread.setDaemon(true);
        return thread;
    });

    public void start() {
        tickExecutor.scheduleAtFixedRate(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    public void submit(Runnable command) {
        commandQueue.add(command);
    }

    private void tick() {
        Runnable command;
        while ((command = commandQueue.poll()) != null) {
            command.run();
        }
    }

    @Override
    public void close() {
        tickExecutor.shutdownNow();
    }
}
