# 心跳机制

MaDaoGame 采用**客户端主动轮询 + 服务端心跳检测**的混合机制，确保断线检测、状态一致性和自动恢复。

## 核心架构

![心跳架构图](/heartbeat-architecture.svg)

## 心跳周期

![心跳周期图](/heartbeat-cycle.svg)

## 工作原理

### 客户端轮询（common.js）

前端 `common.js` 每 **2 秒** 发起一次 AJAX 轮询：

```javascript
// 核心轮询逻辑
setInterval(() => {
  fetch(`/api/room/${roomId}?playerId=${playerId}`)
    .then(res => res.json())
    .then(data => {
      updateUI(data);
      smartPoll(data);  // 智能跳转判断
    });
}, 2000);
```

### 服务端心跳处理（GameController）

服务端 `GET /api/room/{roomId}` 每次被调用时：

1. 更新对应玩家的 `lastActivity` 时间戳
2. 返回完整的房间快照（状态/阶段/玩家列表/日志/聊天）
3. 支持从内存优先读取，缓存未命中时从 DB 恢复

### SmartPoll 智能跳转

轮询返回数据后，`smartPoll()` 根据状态自动跳转页面：

| 条件 | 跳转页面 |
|------|----------|
| `status === FINISHED` | `result.html` |
| `!alive` | `spectate.html` |
| `phase === GUESS && guess === null` | `guess.html` |
| `phase === GUESS && guess !== null` | `waiting.html` |
| `phase === ACTION && isCurrentPlayer` | `action.html` |

## 断线检测

![断线检测](/disconnect-detection.svg)

### 检测维度

| 维度 | 检测方式 | 阈值 |
|------|----------|:----:|
| 客户端轮询中断 | 2 秒轮询停止 | 即时 |
| 服务端心跳超时 | `lastActivity` 时间戳 | 2 分钟 |
| 房间清理 | `cleanupInactiveRooms()` 定时任务 | 2 分钟 |

### 清理机制

服务端定时任务 `cleanupInactiveRooms()`：
- 扫描所有房间的 `lastActivity` 时间戳
- 超过 2 分钟无活动的 `FINISHED` 状态房间自动清理
- 释放内存和数据库资源

## 断线重连

![重连流程](/reconnect-flow.svg)

### 重连策略

1. **玩家刷新页面或重新打开**：前端自动发起 `GET /game/{roomId}/{playerId}`
2. **服务端识别重连**：根据 `playerId` 查找已存在的玩家记录
3. **状态恢复**：
   - 如果房间仍存活，恢复到对应游戏状态
   - 如果玩家已死亡，进入观战模式
   - 如果房间已清理，返回首页

### 重连允许条件

| 房间状态 | 允许重连 | 说明 |
|----------|:--------:|------|
| WAITING | ✅ | 正常恢复 |
| PLAYING | ✅ | 仅允许已存在的 playerId 重连，拒绝新玩家 |
| FINISHED | ✅（限时） | 2 分钟内可查看结果 |

## 数据一致性保障

### 双副本同步

```
写操作: DB优先写入 → 内存同步
        若DB写入失败 → compute()回滚，内存不被污染

读操作: 内存优先 → 缓存未命中时从DB恢复
```

### 心跳期间的操作保护

- 所有写操作通过 `rooms.compute()` 原子执行
- 同一房间的并发请求串行化处理
- 页面隐藏时心跳仍持续（保证 `lastActivity` 更新）
