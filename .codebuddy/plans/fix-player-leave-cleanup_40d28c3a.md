---
name: fix-player-leave-cleanup
overview: 修复 playerLeave() 方法中玩家离开房间后未从内存和数据库中清除的问题，需要同时补充 PlayerDao.deleteById() 方法。
todos:
  - id: add-deleteById
    content: 在 PlayerDao 中新增 deleteById(String playerId) 方法，用于按玩家ID删除单条数据库记录
    status: completed
  - id: fix-playerLeave
    content: 重构 GameService.playerLeave() 方法：从 DB 删除玩家 → 从 room.players 中移除 → 房间为空则清理
    status: completed
    dependencies:
      - add-deleteById
---

## Bug 描述

当玩家在等候大厅（lobby.html）点击"返回首页"时，该玩家没有从房间中正确清除。当前 `GameService.playerLeave()` 仅将玩家的 `lastActivity` 标记为 `epoch(0)`（主动退出标记），但并未将玩家从内存中的 `room.players` 列表移除，也未从数据库 `player` 表中删除该玩家记录。只有当房间内所有玩家都离开时才会触发 `cleanupRoomData()` 清理整个房间，导致单个玩家离开后数据残留在房间中。

## 修复目标

- 玩家离开时，从 `GameRoom.players` 列表中移除该玩家
- 从数据库 `player` 表中删除该玩家记录
- 删除后检查房间是否为空，若为空则清理整个房间
- 保持对 WAITING 状态房间的正常处理（不影响其他等待玩家）

## 技术方案

### 修改范围

#### 1. PlayerDao — 新增 `deleteById` 方法

在 `PlayerDao.java` 中新增按玩家ID删除单条记录的方法，与已有的 `deleteByRoomId` 形成互补。

**新增方法签名**：

```java
public void deleteById(String playerId)
```

**实现方式**：使用 `PreparedStatement` + `try-with-resources`，与现有 DAO 方法风格一致。

#### 2. GameService.playerLeave() — 重构离开逻辑

将原有的"标记退出 → 检查全员离开"逻辑改为"从 DB 删除玩家 → 从内存移除 → 检查房间是否为空"。

**修改后的流程**：

1. 在 `rooms.compute` 回调中查找内存中的玩家
2. 调用 `playerDao.deleteById(playerId)` 从数据库删除该玩家记录
3. 从 `room.getPlayers()` 中移除该玩家（CopyOnWriteArrayList 的 `remove` 操作）
4. 添加离开日志
5. 若 `room.getPlayers().isEmpty()`，则清理房间并返回 null（移除房间）
6. 否则返回 room（保留房间）

#### 3. allPlayersLeft() — 移除

该方法在修复后将不再被 `playerLeave()` 调用，但 `cleanupInactiveRooms()` 中仍可能通过其他逻辑使用。保留该方法以防其他地方引用，但在 `playerLeave()` 中不再调用它。

### 边界情况处理

- **玩家已在内存中不存在**：仍然尝试从 DB 删除，确保数据一致性
- **WAITING 状态房间**：玩家离开后，`room.playerCount` 保持不变（表示房间最大容量），实际人数由 `players.size()` 决定，无需额外处理
- **PLAYING/FINISHED 状态房间**：如果玩家在游戏中离开，同样会从房间中移除。由于使用了 `CopyOnWriteArrayList`，移除操作是线程安全的
- **并发安全**：整个逻辑在 `rooms.compute` 回调中执行，同一房间的操作被串行化，不会出现并发问题