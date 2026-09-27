package com.legendary.game.world;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 单场景 Tick 线程骨架。
 *
 * <p>场景是游戏服务端的核心串行化边界：玩家、怪物和战斗状态原则上只在场景线程
 * 中修改。网络线程收到移动或攻击请求后，不直接调用实体对象，而是把一个命令放入
 * {@code commandQueue}，由下一次 Tick 按顺序执行。</p>
 *
 * <p>这样做的好处是减少场景对象之间的并发修改和锁竞争，也让同一个 Tick 内的命令
 * 顺序可测试、可复盘。代价是命令不会立即执行，而是要等待下一个 Tick；后续需要通过
 * Tick 耗时、队列长度和处理数量来观察系统是否积压。</p>
 */
public final class Scene implements AutoCloseable {
    /**
     * 网络线程向场景提交的命令队列。
     *
     * <p>{@link LinkedBlockingQueue} 提供线程安全的入队和出队操作，但实体状态本身不
     * 放在网络线程修改；队列只负责跨线程传递“要做什么”。</p>
     */
    private final BlockingQueue<Runnable> commandQueue = new LinkedBlockingQueue<>();
    /** 单线程调度器；它保证当前场景的 Tick 不会被多个线程同时执行。 */
    private final ScheduledExecutorService tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "scene-tick");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 启动场景循环。
     *
     * <p>50 毫秒一次等价于目标 20 Tick/s。这里的周期是调度目标，不是性能承诺；如果
     * 某次 Tick 执行超过 50 毫秒，后续需要通过指标判断是否发生积压。</p>
     */
    public void start() {
        tickExecutor.scheduleAtFixedRate(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * 投递场景命令。
     *
     * <p>调用方可以来自 Netty EventLoop 或测试线程。命令的代码体必须遵守场景线程规则，
     * 不应在提交线程提前修改玩家、怪物或战斗状态。</p>
     */
    public void submit(Runnable command) {
        commandQueue.add(command);
    }

    /**
     * 执行一次场景 Tick：按入队顺序处理当前队列中的命令。
     *
     * <p>当前使用非阻塞 {@code poll()}，所以没有命令时会立即结束；后续可以在这里加入
     * 实体更新、战斗结算、广播收集和 Tick 耗时统计。命令执行异常需要在后续版本中定义
     * 隔离策略，不能让一个玩家的非法命令终止整个场景循环。</p>
     */
    private void tick() {
        Runnable command;
        while ((command = commandQueue.poll()) != null) {
            command.run();
        }
    }

    /** 停止 Tick 调度器；进程关闭时先停止场景，再关闭网络资源。 */
    @Override
    public void close() {
        tickExecutor.shutdownNow();
    }
}
