# 架构概览

MaDaoGame 采用经典的五层分层架构，职责清晰、松耦合。

## 五层架构图

![五层架构全景图](/project-overview-components.svg)

## 分层职责

| 层级 | 组件 | 职责 |
|------|------|------|
| 启动层 | `MadaoGameApplication` | `@SpringBootApplication` 入口，`@ComponentScan` 注册所有组件 |
| 控制层 | `GameController` | 9 页面路由 + 5 AJAX API + 心跳检测 |
| 服务层 | `GameService` | 房间 CRUD / 猜拳判定 / 8 种行动 / 定时清理 |
| 数据访问层 | `GameDao` + `PlayerDao` | game_room 和 player 表的 INSERT/UPDATE/DELETE |
| 工具层 | `DBUtil` + HikariCP + H2 | 连接池管理 + 自动建表 + 静态查询 |

## 架构图连线说明

| 线型 | 含义 | 示例 |
|------|------|------|
| `→ (实线)` | `@Autowired` 注入 / 方法调用 | Ctrl → Srv, GD → DBUtil |
| `-→ (虚线)` | 依赖声明 / 自动扫描 | `@DependsOn`, `@ComponentScan` |
| `{}` | 并发容器 / 锁保护 | `ConcurrentHashMap` + `rooms.compute()` |

## 房间生命周期状态机

![状态机图](/project-overview-state.svg)

### 状态转换速查

| 当前状态 | 触发条件 | 目标状态 | 关键方法 |
|----------|----------|----------|----------|
| `[*]` | 玩家创建房间 | `WAITING` | `createRoom()` |
| `WAITING` | 人满 / 房主强制开始 | `GUESS` | `startGame()` / `forceStartGame()` |
| `GUESS` | 全部出拳 + 胜负 | `ACTION` | `processGuessResult()` |
| `GUESS` | 平局（1/3种手势） | `GUESS` | `processGuessResult()` |
| `ACTION` | 队列空 + 多人 | `GUESS` | `endRound()` |
| `ACTION` | 队列空 + 1人存活 | `FINISHED` | `checkGameEnd()` |
| `FINISHED` | 2分钟无活动 | `[*]` | `cleanupInactiveRooms()` |

## 数据流

### 创建房间 + 加入房间

```mermaid
sequenceDiagram
    actor U as 玩家
    participant Ctrl as GameController
    participant Srv as GameService
    participant PD as PlayerDao
    participant GD as GameDao
    participant DB as H2 Database
    participant Mem as ConcurrentHashMap

    U->>Ctrl: POST /create {count, name}
    Ctrl->>Srv: createRoom(count)
    Srv->>GD: insert(room)
    GD->>DB: INSERT game_room
    Srv->>Mem: rooms.put(id, room)
    Ctrl->>Srv: joinRoom(roomId, name)
    Srv->>Mem: rooms.compute(roomId, lambda)
    Note over Srv,Mem: 原子操作：先到先执行
    Srv->>PD: insert(player)
    PD->>DB: INSERT player(HP=10, city-N)
    Srv->>Mem: room.players.add(player)
    Srv-->>Ctrl: playerId
    Ctrl-->>U: redirect /lobby 或 /game
```

### 猜拳判定流程

```mermaid
sequenceDiagram
    actor U as 玩家
    participant Ctrl as GameController
    participant Srv as GameService
    participant Mem as ConcurrentHashMap

    U->>Ctrl: POST /api/guess {gesture}
    Ctrl->>Srv: submitGuess(playerId, gesture)
    Srv->>Mem: rooms.compute(roomId, lambda)
    Note over Srv,Mem: 原子操作
    Srv->>Srv: 同步内存 p.setGuess(gesture)
    
    alt 未全部出拳
        Srv-->>Ctrl: 等待中
    else 全部出拳
        Srv->>Srv: processGuessResult(room)
        alt 平局(1或3种手势)
            Srv->>Srv: 清空guess round++
        else 有胜负(恰好2种)
            Srv->>Srv: 随机打乱构建actionQueue
            Srv->>Srv: phase=ACTION
        end
    end
    Ctrl-->>U: JSON{status,phase,players}
```

### 行动执行 + 轮询跳转

```mermaid
sequenceDiagram
    actor U as 玩家
    participant Ctrl as GameController
    participant Srv as GameService
    participant JS as common.js

    U->>Ctrl: POST /api/action {actionType, targetId?, city?}
    Ctrl->>Srv: executeAction(playerId, actionType, ...)
    Srv->>Srv: rooms.compute(roomId, lambda)
    Note over Srv: 校验 steps>0? 行动合法?
    Note over Srv: 8种行动 switch(actionType)
    Srv->>Srv: 同步dbPlayer+memPlayer双副本
    Srv->>Srv: nextPlayer() / endRound()
    
    loop 每2秒轮询
        JS->>Ctrl: GET /api/room/{r}?playerId={p}
        Ctrl->>Srv: getRoom(roomId)
        Srv-->>Ctrl: room(内存优先/DB恢复)
        Ctrl-->>JS: JSON{status,phase,alive}
        JS->>JS: smartPoll 智能跳转判断
    end
```

## 文件职责表

### Java 后端（8 文件）

| 文件 | 行数 | 类型 | 核心职责 |
|------|:----:|------|----------|
| `MadaoGameApplication.java` | ~40 | 启动类 | 入口 + `@ComponentScan` |
| `controller/GameController.java` | ~615 | `@Controller` | 9页面路由 + 5 AJAX + 心跳 |
| `service/GameService.java` | ~941 | `@Service` | 房间CRUD/猜拳/8行动/清理 |
| `entity/GameRoom.java` | ~246 | 实体 | 状态管理/actionQueue |
| `entity/Player.java` | ~121 | 实体 | HP钳制/装备/位置 |
| `dao/GameDao.java` | ~121 | `@Repository` | game_room 表CRUD |
| `dao/PlayerDao.java` | ~199 | `@Repository` | player 表CRUD |
| `util/DBUtil.java` | ~130 | `@Component` | 连接池+建表 |

### 前端模板（7 HTML + 1 Fragment）

| 文件 | 说明 |
|------|------|
| `index.html` | 首页（创建/加入表单） |
| `lobby.html` | 等待大厅（玩家列表+聊天） |
| `guess.html` | 猜拳界面 |
| `action.html` | 行动界面（8操作+目标选择） |
| `waiting.html` | 等待他人 |
| `spectate.html` | 观战模式 |
| `result.html` | 结果展示 |
| `fragments/rules.html` | 规则弹窗片段 |

## 设计模式一览

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| 模板方法 | `common.js` → `createRoomPoller(config)` | 轮询骨架统一，回调注入差异 |
| 策略模式 | `executeAction()` → `switch(actionType)` | 8种行动对应8个策略分支 |
| 门面模式 | `GameService` | 统一对外API，屏蔽多层复杂性 |
| 副本模式 | `GameService` 写入逻辑 | DB优先写入→内存同步双副本 |
| 代理模式 | `rooms.compute()` | 按房间粒度串行化写入 |

## 配套图片

| 图片 | 说明 |
|------|------|
| ![部署拓扑图](/project-overview-deployment.svg) | 运行时部署拓扑 |
| ![UML 类图](/MaDaoGame-Web%20UML%20类图.svg) | 核心类关系图 |
