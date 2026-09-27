# Protocol Buffers 组件说明

## 1. 组件定位

`Protocol Buffers` 简称 Protobuf，是一种跨语言的结构化数据序列化方案。

它在本项目中的职责是：

- 定义客户端和服务端之间可以传输哪些消息。
- 定义每种消息包含哪些字段。
- 通过 `.proto` 文件生成 Java 和 C# 代码。
- 把 Java 或 C# 对象编码成二进制消息体。
- 把二进制消息体解码回 Java 或 C# 对象。

它不负责：

- TCP 连接。
- 端口监听。
- 半包和粘包。
- 心跳超时。
- 消息分发。
- 登录状态。
- 场景 Tick。
- 战斗逻辑。

这些能力分别由 `Netty`、自定义消息头、Session、场景和战斗模块完成。

## 2. 为什么项目使用 Protocol Buffers

游戏实时消息通常比较频繁，例如移动、心跳、攻击和状态广播。相比 JSON，Protobuf 更适合实时主协议：

- 二进制格式更紧凑。
- 字段类型明确。
- 可以从同一份 `.proto` 生成 Java 和 C# 代码。
- 字段编号支持协议演进和兼容。
- 编解码逻辑由生成代码完成，减少手写解析错误。

本项目最终客户端是 Unity C#，服务端是 Java，因此共享 Schema 很重要。

```text
同一份 game_protocol.proto
    -> 生成 Java 消息类，供 game-server 和 game-bot 使用
    -> 生成 C# 消息类，供 Unity 客户端使用
```

## 3. 当前项目中如何使用

当前协议文件：

```text
game-protocol/src/main/proto/game_protocol.proto
```

Maven 构建时会通过 `protobuf-maven-plugin` 调用 `protoc`，生成 Java 源码：

```text
game-protocol/target/generated-sources/protobuf/java/
```

这些生成代码属于构建产物，不手工修改。真正维护的是 `.proto` 文件。

当前已定义的消息包括：

- `LoginRequest`
- `LoginResponse`
- `HeartbeatRequest`
- `HeartbeatResponse`
- `EnterSceneRequest`
- `EnterSceneResponse`
- `MoveRequest`
- `PlayerPositionSnapshot`
- `SceneSnapshot`

当前还未实现：

- Java 网络层中的 Protobuf 解码器。
- `messageType` 和具体 Protobuf 类型的映射。
- C# 代码生成。
- Bot 真实收发 Protobuf 消息。

## 4. `.proto` 是什么

`.proto` 是 Protobuf 的 Schema 文件。它看起来像一种特殊语法，本质上是跨语言消息结构定义。

示例：

```proto
message LoginRequest {
  string account_name = 1;
  string client_version = 2;
}
```

含义是定义一个 `LoginRequest` 消息，里面有两个字段：

| 字段名 | 类型 | 字段编号 |
|---|---|---|
| `account_name` | `string` | `1` |
| `client_version` | `string` | `2` |

它类似 Java 类，但不是 Java 类：

```text
.proto message
    -> 跨语言协议定义

Java class
    -> 某一种语言里的运行时代码
```

## 5. 字段编号为什么重要

Protobuf 二进制数据中主要依赖字段编号，而不是字段名。

```proto
string account_name = 1;
```

这里的 `1` 不是赋值，而是字段编号。它表示：

```text
字段编号 1 对应 account_name
```

字段编号一旦发布就不能随意复用。否则旧客户端和新服务端可能对同一个编号产生不同理解。

错误示例：

```proto
message LoginRequest {
  string device_id = 1; // 错误：复用了原 account_name 的编号
}
```

正确做法：

```proto
message LoginRequest {
  reserved 1;
  reserved "account_name";

  string client_version = 2;
  string device_id = 3;
}
```

项目规则中“已发布字段编号不得复用”就是为了避免这种协议兼容问题。

## 6. enum 为什么保留 UNSPECIFIED

当前方向枚举：

```proto
enum Direction {
  DIRECTION_UNSPECIFIED = 0;
  UP = 1;
  DOWN = 2;
  LEFT = 3;
  RIGHT = 4;
}
```

Protobuf 中枚举默认值是编号 `0`。如果没有 `UNSPECIFIED = 0`，就很难区分：

- 客户端真的选择了某个业务值。
- 客户端没有填写这个字段。
- 服务端拿到了默认值。

因此项目规定枚举保留 `*_UNSPECIFIED = 0`。

## 7. Protobuf 和自定义消息头的关系

Protobuf 只负责消息体，不负责完整网络帧。

本项目一帧网络消息分为：

```text
自定义消息头 14 bytes
    -> magic
    -> protocolVersion
    -> flags
    -> messageType
    -> requestId
    -> bodyLength

Protobuf body
    -> LoginRequest / MoveRequest / SceneSnapshot 等消息体
```

为什么需要自定义消息头？

- Protobuf body 自己不包含项目级协议版本。
- Protobuf body 自己不告诉 Netty 这是什么业务消息类型。
- Protobuf body 自己不负责请求和响应关联。
- TCP 分帧需要明确长度字段。

因此设计分层是：

```text
LengthFieldBasedFrameDecoder
    -> 根据 bodyLength 切出完整帧

自定义消息头解码器
    -> 读取 messageType、requestId、bodyLength

Protobuf 解码器
    -> 根据 messageType 把 body 解码成具体消息对象
```

## 8. 项目中的消息示例

### 登录请求

```proto
message LoginRequest {
  string account_name = 1;
  string client_version = 2;
}
```

当前定位是本地演示登录，不是真实商业账号系统。后续 MySQL、密码、Token 等不会进入第一阶段主链路。

### 移动请求

```proto
message MoveRequest {
  Direction direction = 1;
  uint32 input_sequence = 2;
}
```

客户端只提交方向和输入序号，不提交最终坐标。服务端根据玩家速度、地图边界和当前 Tick 计算最终坐标。

### 场景快照

```proto
message SceneSnapshot {
  string scene_id = 1;
  repeated PlayerPositionSnapshot players = 2;
}
```

`repeated` 表示列表。后续客户端进入场景或重连时，可以通过快照恢复当前场景状态。

## 9. 常见误区

- 误区一：Protobuf 是网络框架。
  - 正确理解：Protobuf 只是结构化数据编码方案，网络连接由 Netty 和 TCP 负责。

- 误区二：`.proto` 只是 Java 类的另一种写法。
  - 正确理解：`.proto` 是跨语言协议契约，可以生成 Java、C# 等多种语言代码。

- 误区三：字段名最重要。
  - 正确理解：二进制兼容主要依赖字段编号，字段编号发布后不能复用。

- 误区四：Protobuf 可以替代自定义消息头。
  - 正确理解：Protobuf 只负责 body。消息类型、请求编号、协议版本和长度仍由外层协议处理。

## 10. 实验验证

当前阶段可以做的最小实验：

1. 修改 `game_protocol.proto`。
2. 执行 Maven 构建。
3. 查看 `target/generated-sources/protobuf/java/` 是否生成对应 Java 类。
4. 在测试中使用 `toByteArray()` 和 `parseFrom()` 验证对象和字节之间的转换。

后续应增加协议回归测试，例如：

- `LoginRequest` 编码后能正确解码。
- 未知字段不会破坏旧消息读取。
- 枚举默认值能被识别为 `UNSPECIFIED`。
- 删除字段必须使用 `reserved`。

## 11. 复盘问题

完成当前阶段后，应能回答：

- `.proto` 文件解决什么问题？
- Protobuf 和 JSON 的主要区别是什么？
- 为什么字段编号不能复用？
- 为什么枚举需要 `UNSPECIFIED = 0`？
- Protobuf body 和自定义消息头分别负责什么？
- 为什么 `MoveRequest` 不允许客户端提交最终坐标？
- 生成的 Java 代码为什么不能手工修改？
