# Unity 客户端规划

## 1. 功能目标

Unity 客户端是 Legendary Online 的真实表现层，用于完成 2D ARPG 的输入、UI、动画、地图表现和多人状态展示。

客户端不负责最终游戏判定。客户端只发送操作意图，例如登录信息、移动方向、输入序号和攻击指令；服务端负责最终坐标、命中、伤害、死亡、奖励和状态广播。

第一版目标不是完整商业 MMORPG 客户端，而是一个可真实联机的企业级垂直切片客户端：代码结构清晰、协议边界明确、线程模型可解释、断线和错误状态可观察。

## 2. 学习目标

- 理解 Unity 2D 项目结构和生命周期。
- 理解 C# 基础语法、异步网络和 Unity 主线程限制。
- 理解 Unity 客户端如何接入 TCP + Protocol Buffers。
- 理解客户端表现层和服务端权威逻辑的边界。
- 理解快照同步、插值、动画和输入意图之间的关系。
- 理解为什么网络线程不能直接操作 Unity `GameObject`。

## 3. 客户端职责边界

### 客户端负责

- 登录界面、连接状态和错误提示。
- 采集玩家输入，例如方向键、摇杆、技能按钮。
- 编码并发送 Protobuf 请求。
- 接收服务端快照和事件。
- 在 Unity 主线程创建、更新和销毁角色对象。
- 播放移动、攻击、受击和死亡动画。
- 对服务端权威坐标做表现层插值。

### 客户端不负责

- 不提交最终坐标。
- 不提交伤害值。
- 不判定攻击命中。
- 不判定怪物死亡。
- 不发放经验、金币或掉落。
- 不直接相信本地预测结果作为最终状态。

## 4. 推荐目录结构

Unity 项目后续放在 `game-client/` 下，建议结构如下：

```text
game-client/
├── Assets/
│   ├── Scenes/
│   │   └── Main.unity
│   ├── Scripts/
│   │   ├── App/
│   │   │   └── GameClientBootstrap.cs
│   │   ├── Network/
│   │   │   ├── TcpGameClient.cs
│   │   │   ├── ProtocolCodec.cs
│   │   │   ├── NetworkMessageDispatcher.cs
│   │   │   └── MainThreadDispatcher.cs
│   │   ├── Protocol/
│   │   │   └── generated C# protobuf files
│   │   ├── Player/
│   │   │   ├── LocalPlayerController.cs
│   │   │   └── RemotePlayerView.cs
│   │   ├── Scene/
│   │   │   ├── SceneStateStore.cs
│   │   │   └── EntityViewFactory.cs
│   │   └── UI/
│   │       ├── LoginPanel.cs
│   │       └── ConnectionStatusView.cs
│   ├── Prefabs/
│   │   ├── LocalPlayer.prefab
│   │   ├── RemotePlayer.prefab
│   │   └── Monster.prefab
│   └── Art/
│       ├── Characters/
│       ├── Monsters/
│       └── Tiles/
├── Packages/
└── ProjectSettings/
```

第一版可以先使用简单占位素材，但目录和代码边界要按正式客户端组织，避免后续迁移成本过高。

## 5. 协议接入方案

### 5.1 C# Protobuf 生成

服务端和客户端共享同一份 `.proto`：

```text
game-protocol/src/main/proto/game_protocol.proto
```

后续需要补充 C# 生成流程，把同一份协议生成到 Unity 可引用的 C# 代码中。生成代码只表示消息体，仍然需要 Unity 侧实现 14 字节消息头和 TCP 分帧。

### 5.2 Unity 侧消息帧

Unity 发送和接收的帧结构必须与 Java 服务端一致：

```text
magic           2 bytes
protocolVersion 1 byte
flags           1 byte
messageType     2 bytes
requestId       4 bytes
bodyLength      4 bytes
protobuf body   bodyLength bytes
```

Unity 侧 `ProtocolCodec` 的职责：

1. 写入 14 字节 header。
2. 将 Protobuf 消息体写入 body。
3. 从 TCP 字节流中恢复完整帧。
4. 校验 `magic`、`protocolVersion` 和 `bodyLength`。
5. 根据 `messageType` 解析成对应 C# Protobuf 对象。

## 6. 线程模型

Unity 有一个关键限制：大多数 `GameObject`、`Transform`、UI 和动画 API 只能在 Unity 主线程访问。

因此客户端网络层应采用如下模型：

```text
TCP 网络线程
    -> 接收字节
    -> 分帧和解码
    -> 生成消息对象
    -> 投递到主线程队列

Unity 主线程
    -> 每帧 Update 中消费消息队列
    -> 更新 UI、角色、地图和动画
```

禁止在网络线程中直接执行：

- `Instantiate`
- `Destroy`
- 修改 `Transform.position`
- 修改 UI 文本或按钮状态
- 播放 Animator 状态

如果违反这个边界，轻则出现偶发错误，重则导致 Unity 线程安全问题和难以复现的崩溃。

## 7. 阶段拆解

### 阶段一：Unity 空项目和基础场景

目标：建立 Unity 2D 工程、基础场景、摄像机和目录结构。

内部步骤：

1. 创建 `game-client` Unity 2D 项目。
2. 建立 `Assets/Scripts`、`Assets/Prefabs`、`Assets/Scenes` 等目录。
3. 创建 `Main.unity` 场景。
4. 放置临时地图和本地玩家占位对象。
5. 提交 Unity 必要工程文件，忽略 Library、Temp、Logs 等本地缓存。

验收：Unity 能打开项目并运行空场景。

### 阶段二：C# 协议生成和本地编解码

目标：Unity 能使用与 Java 服务端一致的 Protobuf 消息类。

内部步骤：

1. 为 `.proto` 增加 C# 生成方式。
2. 引入 `Google.Protobuf`。
3. 生成 C# 消息类。
4. 编写 `ProtocolCodec`。
5. 用登录消息做本地编码和解码验证。

验收：Unity 侧能构造 `LoginRequest`，编码为服务端兼容的帧。

### 阶段三：TCP 登录和心跳

目标：Unity 客户端能连接 Java 服务端并完成登录、心跳。

内部步骤：

1. 实现 `TcpGameClient`。
2. 实现连接、断开和重连入口。
3. 实现登录 UI。
4. 发送 `LoginRequest`。
5. 接收 `LoginResponse`。
6. 登录成功后定时发送 `HeartbeatRequest`。
7. 收到断线或异常时更新 UI 状态。

验收：启动 Java 服务端后，Unity 客户端登录成功并显示连接状态。

### 阶段四：进入场景和快照展示

目标：Unity 根据服务端场景快照创建玩家和怪物表现对象。

内部步骤：

1. 发送 `EnterSceneRequest`。
2. 接收 `EnterSceneResponse`。
3. 接收 `SceneSnapshot`。
4. 创建本地玩家、远程玩家和怪物对象。
5. 维护 `entityId -> GameObject` 映射。
6. 处理实体离场或移除事件。

验收：两个客户端进入同一场景后可以看到彼此。

### 阶段五：移动输入和插值表现

目标：客户端发送移动意图，服务端返回权威坐标，Unity 做表现层平滑。

内部步骤：

1. 采集键盘或摇杆方向。
2. 生成递增 `inputSequence`。
3. 发送 `MoveRequest`。
4. 接收 `PlayerPositionSnapshot`。
5. 本地玩家和远程玩家按服务端坐标更新。
6. 使用插值减少视觉跳动。

验收：客户端不提交最终坐标，两个客户端移动可互相看到。

### 阶段六：攻击和动画

目标：Unity 发送攻击意图并播放服务端广播结果。

内部步骤：

1. 采集攻击按钮或快捷键。
2. 发送攻击意图，例如技能编号和方向。
3. 接收伤害事件。
4. 播放攻击、受击和死亡动画。
5. 怪物死亡后移除表现对象。

验收：客户端不能伪造伤害，所有伤害和死亡结果来自服务端。

### 阶段七：断线和重连

目标：客户端能在网络断开后提示用户，并在重连后恢复场景状态。

内部步骤：

1. 检测 TCP 断开。
2. 停止发送输入。
3. 显示断线 UI。
4. 点击重连后重新建立 TCP 连接。
5. 重新登录或恢复 Session。
6. 重新拉取场景快照。

验收：关闭服务端或断网时客户端状态清晰，恢复后能重新进入场景。

## 8. 与 Java Bot 的关系

Java Bot 是 Unity 客户端前置验证工具。协议和服务端行为先由 Bot 固化，再接 Unity：

```text
Java Bot 通过
    -> 说明服务端协议和登录心跳链路可用
    -> Unity 按相同协议接入
    -> 如果 Unity 失败，优先检查 C# 编解码、线程切换和 UI 逻辑
```

这能避免服务端和 Unity 同时变化时难以定位问题。

## 9. 测试清单

- Unity 本地构造 `LoginRequest` 并编码成功。
- Java 服务端能解析 Unity 登录请求。
- Unity 能解析 Java `LoginResponse`。
- TCP 半包不会导致消息丢失。
- 未连接时不能点击移动或攻击。
- 登录失败能显示错误状态。
- 断线后 UI 状态正确。
- 网络线程不直接操作 Unity 对象。
- 两个客户端同场景移动可见。
- 客户端伪造坐标或伤害不会被服务端接受。

## 10. 当前边界

- 当前仓库尚未创建 Unity 工程。
- 当前尚未生成 C# Protobuf 代码。
- 当前真实 TCP 联调由 Java Bot 承担。
- Unity 阶段必须等服务端协议和最小场景链路更稳定后进入，避免客户端追随频繁变更的协议反复返工。

## 11. 复盘问题

- 为什么 Unity 客户端不能提交最终坐标？
- 为什么网络线程不能直接修改 `GameObject`？
- Unity 侧为什么仍然需要实现 14 字节消息头？
- Java Bot 和 Unity 客户端在联调中的职责有什么区别？
- 什么情况下才需要客户端预测？第一版为什么可以先不做复杂预测？
- 断线重连后为什么需要重新拉取场景快照？
