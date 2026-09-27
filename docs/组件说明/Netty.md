# Netty 组件说明

## 1. 组件定位

`Netty` 是一个 Java 网络通信框架。它不是游戏框架，也不负责登录、移动、战斗或数据库访问。它在本项目中的职责是：

- 监听 TCP 端口。
- 接收客户端连接。
- 管理连接上的读写事件。
- 处理 TCP 字节流。
- 通过 `ChannelPipeline` 串联解码、编码和业务分发处理器。

可以把 `Netty` 理解为服务端和客户端之间的网络层基础设施。

```text
Unity 客户端
    -> TCP 字节流
    -> Netty Channel
    -> ChannelPipeline
    -> 协议解码
    -> Session / Scene CommandQueue
```

## 2. 为什么项目使用 Netty

游戏服务端通常需要维护大量长连接，并且移动、心跳、攻击等消息频率较高。直接使用 Java 原生 `Socket` 可以学习底层原理，但需要自己处理很多重复且容易出错的细节，例如：

- 连接接入和关闭。
- 读写事件监听。
- 线程模型。
- 半包和粘包。
- 连接空闲检测。
- 高并发下的资源释放。

`Netty` 对这些网络基础能力做了成熟封装，招聘 JD 中也经常要求熟悉 `Netty`、TCP 长连接和网络编程。因此第一阶段选择 `Netty + TCP` 作为实时通信主线。

## 3. 当前项目中如何使用

当前代码只完成了 Netty 服务端骨架，还没有完成完整协议编解码和消息分发。

主要入口：

- `game-server/game-server-bootstrap/src/main/java/com/legendary/game/bootstrap/GameServerApplication.java`
- `game-server/game-network/src/main/java/com/legendary/game/network/GameServer.java`
- `game-server/game-network/src/main/java/com/legendary/game/network/GameChannelInitializer.java`

当前启动流程：

```text
GameServerApplication.main
    -> 创建 Scene
    -> 创建 GameServer
    -> Scene.start()
    -> GameServer.start()
    -> Netty bind(9000)
```

当前网络处理链：

```text
ChannelPipeline
    -> LoggingHandler
    -> LengthFieldBasedFrameDecoder
```

下一阶段会继续接入：

```text
ChannelPipeline
    -> LoggingHandler
    -> LengthFieldBasedFrameDecoder
    -> 自定义消息头解码器
    -> Protobuf 消息体解码器
    -> 消息分发处理器
    -> Session / Scene CommandQueue
```

## 4. 核心概念

### 4.1 ServerBootstrap

`ServerBootstrap` 是 Netty 服务端启动器。它负责组装服务端启动需要的配置，例如：

- 使用哪种网络通道实现。
- 使用哪些线程组。
- 监听哪个端口。
- 每个客户端连接建立后安装哪些处理器。

当前项目中：

```java
ServerBootstrap bootstrap = new ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(new GameChannelInitializer())
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.SO_KEEPALIVE, true);
```

### 4.2 Boss EventLoopGroup

`bossGroup` 负责接收新的 TCP 连接。它不处理具体游戏消息。

当前项目使用：

```java
bossGroup = new NioEventLoopGroup(1);
```

这里的 `1` 表示只使用一个 Boss 线程。第一阶段本地学习和演示足够使用一个 Boss 线程。

### 4.3 Worker EventLoopGroup

`workerGroup` 负责已经建立连接上的读写事件，例如：

- 客户端发来字节。
- 服务端写回字节。
- Pipeline 中的入站处理器执行。

注意：Worker 线程仍然是网络线程，不应该直接执行复杂游戏逻辑。移动、攻击等进入场景的操作，需要转换成命令放入场景队列，由场景 Tick 线程处理。

### 4.4 Channel

`Channel` 表示一条网络连接，或者服务端监听端口本身。

在当前项目中：

- `serverChannel` 表示服务端监听端口的 Channel。
- 后续每个客户端连接也会有自己的 Channel。

未来 `Session` 会和客户端 Channel 建立关联。

### 4.5 ChannelPipeline

`ChannelPipeline` 是每条连接上的处理链。一个客户端连接建立后，会创建自己的 Pipeline。

入站数据大致这样流动：

```text
TCP 字节
    -> Handler 1
    -> Handler 2
    -> Handler 3
    -> 业务处理
```

出站数据则反向流动。

Pipeline 的好处是可以把不同职责拆开：

- 分帧处理器只负责解决半包和粘包。
- 消息头解码器只负责解析 header。
- Protobuf 解码器只负责把 body 转成对象。
- 消息分发器只负责根据消息类型路由。

### 4.6 LengthFieldBasedFrameDecoder

TCP 是字节流协议，不保留消息边界。客户端发送两条消息，服务端一次读取时可能出现：

```text
半包：只读到一条消息的一部分
粘包：一次读到多条消息拼在一起
```

`LengthFieldBasedFrameDecoder` 通过消息中的长度字段判断一帧消息是否完整。

当前项目第一版消息头规划为 14 字节：

```text
magic             2 bytes
protocolVersion   1 byte
flags             1 byte
messageType       2 bytes
requestId         4 bytes
bodyLength        4 bytes
```

因此 `bodyLength` 的偏移是 10，长度是 4：

```java
new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 10, 4, 0, 0)
```

这里的含义是：

- `MAX_FRAME_LENGTH`：允许的最大帧长度。
- `10`：长度字段从第 10 个字节开始。
- `4`：长度字段占 4 字节。
- `0`：长度修正值。
- `0`：解码后不丢弃任何头部字节。

当前保留完整帧，是为了下一阶段继续读取自定义消息头。

## 5. 为什么网络线程不直接执行游戏逻辑

如果在 Netty Worker 线程中直接修改玩家坐标或执行战斗逻辑，会有几个问题：

- 多个连接可能同时修改同一个场景对象，容易出现并发错误。
- 某个玩家的复杂逻辑会阻塞同一个 EventLoop 上的其他连接。
- 场景内命令顺序难以复现，不利于测试和排查。
- 后续战斗、掉落、死亡和广播逻辑会变得依赖锁，复杂度快速上升。

因此项目规定：

```text
Netty Worker 线程
    -> 解码和基础校验
    -> 投递 SceneCommand
    -> Scene Tick 线程顺序处理
```

## 6. 当前已实现和未实现

已实现：

- Netty 服务端启动。
- Boss/Worker 线程组。
- 监听 9000 端口。
- 连接 Pipeline 初始化。
- 日志处理器。
- 长度字段分帧器。

未实现：

- 自定义消息头对象。
- 消息头编码器和解码器。
- Protobuf 消息体解码。
- 消息类型路由。
- Session 管理。
- 心跳和空闲检测。
- 登录、移动、攻击业务处理。

## 7. 实验验证

当前阶段可以做的最小实验：

1. 启动服务端。
2. 观察日志是否出现：

```text
Netty listening on port 9000
Game server started
```

3. 使用 TCP 客户端连接 `localhost:9000`。
4. 观察 Netty 日志是否出现连接事件。

后续接入编码器后，再增加半包、粘包和非法长度字段测试。

## 8. 常见误区

- 误区一：Netty 负责业务逻辑。
  - 正确理解：Netty 只负责网络事件和字节处理，业务逻辑由项目自己的模块完成。

- 误区二：TCP 一次 `write` 对应服务端一次 `read`。
  - 正确理解：TCP 是字节流，必须通过协议分帧恢复消息边界。

- 误区三：Worker 线程可以直接修改场景对象。
  - 正确理解：Worker 是网络线程，场景状态应由 Tick 线程顺序修改。

- 误区四：`SO_KEEPALIVE` 可以替代游戏心跳。
  - 正确理解：TCP KeepAlive 是系统层探测，周期长且不可控；游戏仍需要应用层心跳。

## 9. 复盘问题

完成当前阶段后，应能回答：

- `ServerBootstrap` 解决什么问题？
- Boss 和 Worker `EventLoopGroup` 有什么区别？
- 什么是 `ChannelPipeline`？
- 为什么 TCP 会有半包和粘包？
- 当前 `LengthFieldBasedFrameDecoder` 的 `10` 和 `4` 分别是什么意思？
- 为什么 Netty Worker 线程不能直接修改玩家坐标？
- 下一阶段应该在 Pipeline 中接入哪些处理器？
