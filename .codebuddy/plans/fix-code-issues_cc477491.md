---
name: fix-code-issues
overview: 修复 MaDaoGame 项目中的 12 个代码问题，涵盖 Java 版本配置、DAO 异常处理、线程安全、NPE 防护、XSS 漏洞、CSS 废弃属性、JS API 更新、AJAX 轮询改造、公共资源抽取等。
todos:
  - id: fix-pom-java-version
    content: 修改 pom.xml 中 Java 版本从 25 改为 21
    status: completed
  - id: fix-dao-exception-and-resultset
    content: 修复 GameDao.java 和 PlayerDao.java：SQLException 包装为 RuntimeException，ResultSet 放入 try-with-resources
    status: completed
  - id: fix-service-thread-safety
    content: 改造 GameService.java：移除 synchronized，使用 ConcurrentHashMap.compute 实现按房间细粒度锁
    status: completed
  - id: fix-controller-npe
    content: 修复 GameController.java 中 executeAction 的 dbPlayer NPE 风险
    status: completed
  - id: create-common-css-js
    content: 创建公共文件 common.css 和 common.js，抽取规则弹窗样式/逻辑、HTML 转义函数、通用 UI 更新函数
    status: completed
  - id: fix-html-all-pages
    content: 修复所有 7 个 HTML 文件：引入公共文件、word-wrap 替换、Content-Type 添加、XSS 转义、textContent 空值检查
    status: completed
    dependencies:
      - create-common-css-js
  - id: fix-lobby-ajax-and-clipboard
    content: 改造 lobby.html：meta refresh 改为 AJAX 轮询，execCommand 改为 navigator.clipboard.writeText
    status: completed
    dependencies:
      - create-common-css-js
---

## 修复范围

按用户指定要求，对马刀游戏项目进行以下问题的逐一修复：

**Java 后端修复：**

1. `pom.xml` 中 Java 版本从 25 改为 21
2. `GameDao.java` 和 `PlayerDao.java` 的 DAO 层异常处理：将 SQLException 包装为 RuntimeException 向上抛出
3. `GameDao.java` 中 `findById` 方法的 ResultSet 放入 try-with-resources
4. `GameService.java` 线程安全改造：移除所有 synchronized 方法，改用 `ConcurrentHashMap.compute` 实现按房间细粒度锁
5. `GameController.java` 中 `executeAction` 的 NPE 防护：在 `dbPlayer` 为 null 时提前返回

**HTML 前端修复：**

6. 所有 7 个 HTML 文件中 `word-wrap: break-word` 替换为 `overflow-wrap: break-word`
7. 所有 7 个 HTML 文件中添加 `<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">`
8. `lobby.html` 中 `document.execCommand('copy')` 替换为 `navigator.clipboard.writeText()`
9. `lobby.html` 中 `<meta http-equiv="refresh">` 整页刷新改为 AJAX 轮询
10. 所有 HTML 文件中使用 innerHTML 渲染用户内容的地方添加 HTML 转义函数（防 XSS）
11. 抽取公共 CSS 文件 `common.css`（规则弹窗样式）和公共 JS 文件 `common.js`（规则弹窗逻辑、玩家卡片渲染、日志/聊天更新等）
12. `guess.html`、`action.html`、`waiting.html`、`spectate.html`、`result.html` 中 `chatList.children[i].textContent` 访问前添加空值检查

**不修改的问题：** 弃权逻辑、chat 路由、血祭公式保持现状不变。

## 技术方案

### 1. pom.xml Java 版本修复

将 `<java.version>25</java.version>` 改为 `<java.version>21</java.version>`。Spring Boot 3.4.x 官方支持 Java 17/21/22/23，21 是当前推荐的 LTS 版本。

### 2. DAO 层异常处理修复

在 `GameDao.java` 和 `PlayerDao.java` 的所有方法中，将 `catch (SQLException e) { e.printStackTrace(); }` 改为 `catch (SQLException e) { throw new RuntimeException("数据库操作失败", e); }`，确保上层调用方能感知并处理异常。

### 3. ResultSet 资源关闭

在 `GameDao.findById` 方法中，将 `ResultSet rs` 也放入 try-with-resources 语句中，确保资源在任何情况下都能被正确关闭。

### 4. GameService 线程安全改造

**核心变更：** 移除所有 `synchronized` 关键字，改用 `ConcurrentHashMap.compute` 实现按房间粒度的锁。

**实现方式：** 在每个需要互斥的方法中，通过 `rooms.compute(roomId, (key, room) -> { ... })` 将房间级操作原子化。对于不需要特定 roomId 的操作（如 `createRoom`），使用新的 `ConcurrentHashMap` 独立管理锁对象。

**关键设计：**

- `createRoom` — 不需要细粒度锁，直接操作 rooms map
- `joinRoom` — 使用 `rooms.compute(roomId, ...)` 原子化整个加入流程
- `submitGuess` — 使用 `rooms.compute(roomId, ...)` 原子化猜拳提交
- `executeAction` — 使用 `rooms.compute(roomId, ...)` 原子化行动执行
- `forceStartGame` — 使用 `rooms.compute(roomId, ...)` 原子化强制开始
- `playerLeave` — 使用 `rooms.compute(roomId, ...)` 原子化离开操作
- `getRoom` — 保持只读，不需要锁

**注意：** `compute` 方法内部不能直接修改 Map（如 `rooms.remove`），需要在 compute 回调中返回 null 来标记删除。`cleanupRoom` 方法需要在 compute 外部处理或使用 `computeIfPresent` 变体。

### 5. NPE 防护修复

在 `GameController.apiAction` 和相关方法中，`executeAction` 被调用前检查 `dbPlayer` 是否为 null。实际 NPE 发生在 `GameService.executeAction` 方法的第 487 行 `getRoom(dbPlayer.getRoomId())`。修复方式：在 `executeAction` 方法开头添加 null 检查。

### 6. XSS 防护

创建统一的 HTML 转义函数 `escapeHtml(str)`，对所有通过 `innerHTML` 渲染的用户可控内容（聊天消息、玩家昵称、操作日志）进行转义。将 `<` `>` `&` `"` `'` 替换为对应的 HTML 实体。

### 7. word-wrap 替换

全局搜索所有 HTML 文件中的 `word-wrap: break-word`，替换为 `overflow-wrap: break-word`。

### 8. execCommand 替换

`lobby.html` 中的复制功能改用 `navigator.clipboard.writeText(roomId)` 实现，并处理异步错误。

### 9. lobby.html AJAX 改造

移除 `<meta http-equiv="refresh">`，改为使用 `setInterval` + `fetch(/api/room/...)` 的 AJAX 轮询模式，与其他页面保持一致。轮询频率设为 3 秒，与原有刷新频率一致。

### 10. 公共文件抽取

创建两个新文件：

- `src/main/resources/static/css/common.css` — 包含规则弹窗的 CSS 样式
- `src/main/resources/static/js/common.js` — 包含规则弹窗逻辑（toggleRules/restoreRulesPopup/click 监听）、HTML 转义函数 escapeHtml、通用的 updatePlayers/updateLogs/updateChat 函数、聊天发送逻辑

各 HTML 页面通过 `<link>` 和 `<script>` 引入公共文件，移除自身重复代码。

### 11. textContent 空值检查

在 `guess.html`、`action.html`、`waiting.html`、`spectate.html`、`result.html` 的 `updateChat` 和 `updateLogs` 函数中，访问 `chatList.children[i]` 和 `logList.children[i]` 前先检查是否存在。

### 12. Content-Type 字符集

在所有 HTML 文件的 `<head>` 中添加 `<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">`。

## 目录结构

```
src/
├── main/
│   ├── java/com/madao/game/
│   │   ├── MadaoGameApplication.java          # [不变]
│   │   ├── controller/
│   │   │   └── GameController.java            # [MODIFY] NPE防护
│   │   ├── dao/
│   │   │   ├── GameDao.java                   # [MODIFY] 异常处理+ResultSet
│   │   │   └── PlayerDao.java                 # [MODIFY] 异常处理
│   │   ├── entity/
│   │   │   ├── GameRoom.java                  # [不变]
│   │   │   └── Player.java                   # [不变]
│   │   ├── service/
│   │   │   └── GameService.java               # [MODIFY] 线程安全改造
│   │   └── util/
│   │       └── DBUtil.java                    # [不变]
│   └── resources/
│       ├── application.properties             # [不变]
│       ├── static/
│       │   ├── css/
│       │   │   └── common.css                 # [NEW] 公共CSS
│       │   └── js/
│       │       └── common.js                  # [NEW] 公共JS
│       └── templates/
│           ├── index.html                     # [MODIFY] 引入公共文件+word-wrap+Content-Type
│           ├── lobby.html                     # [MODIFY] AJAX改造+execCommand+公共文件+word-wrap+Content-Type
│           ├── guess.html                     # [MODIFY] XSS+公共文件+word-wrap+空值检查+Content-Type
│           ├── waiting.html                   # [MODIFY] XSS+公共文件+word-wrap+空值检查+Content-Type
│           ├── action.html                    # [MODIFY] XSS+公共文件+word-wrap+空值检查+Content-Type
│           ├── spectate.html                  # [MODIFY] XSS+公共文件+word-wrap+空值检查+Content-Type
│           └── result.html                    # [MODIFY] XSS+公共文件+word-wrap+空值检查+Content-Type
└── pom.xml                                    # [MODIFY] Java版本
```