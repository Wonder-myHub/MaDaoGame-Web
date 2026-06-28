---
layout: home

hero:
  name: "MaDaoGame"
  text: "马刀游戏"
  tagline: 多人回合制网页对战游戏 · Spring Boot + Thymeleaf + H2 构建
  image:
    src: /project-overview-components.svg
    alt: 系统架构图
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/getting-started
    - theme: alt
      text: 游戏规则
      link: /guide/game-rules
    - theme: alt
      text: API 参考
      link: /api/reference

features:
  - icon: 🏗️
    title: 五层分层架构
    details: 控制层 → 服务层 → 数据访问层 → 工具层 → 内存存储，职责清晰，松耦合设计
    link: /architecture/overview
    linkText: 查看架构
  - icon: ⚡
    title: 高并发设计
    details: ConcurrentHashMap 按房间粒度加锁，双副本同步策略，CopyOnWriteArrayList 读写分离
    link: /architecture/concurrency
    linkText: 并发模型
  - icon: ❤️
    title: 心跳保活机制
    details: 2秒客户端轮询 + 多维度健康检测 + 自动重连恢复，保障游戏状态一致性
    link: /architecture/heartbeat
    linkText: 心跳机制
  - icon: 🎮
    title: 石头剪刀布对战
    details: 8种行动类型（移动/买马/买刀/踢/刺/血祭/撵入），步数驱动的回合制策略玩法
    link: /guide/game-rules
    linkText: 完整规则
  - icon: 📡
    title: RESTful API
    details: 9 个页面路由 + 5 个 AJAX JSON API，核心轮询接口返回完整房间快照
    link: /api/reference
    linkText: API 速查
  - icon: 🚀
    title: Spring Boot 生态
    details: Spring Boot + Thymeleaf + HikariCP + H2 文件数据库，零外部依赖，开箱即用
    link: /guide/getting-started
    linkText: 环境搭建
---

## 技术栈

<div class="tech-stack">

| 层级 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 2.x | 主框架，自动配置 + 内置 Tomcat |
| 模板 | Thymeleaf | 服务端渲染，7 个游戏页面 + 1 个片段 |
| 数据库 | H2 File DB | 嵌入式文件数据库，零配置 |
| 连接池 | HikariCP | 高性能 JDBC 连接池 (min=2, max=10) |
| 前端 | Vanilla JS + CSS | 纯原生 JS 实现 2 秒轮询 + SmartPoll |
| 并发 | ConcurrentHashMap | 按房间粒度细粒度锁 |

</div>

## 快速预览

```bash
# 克隆项目
git clone https://github.com/Wonder-myHub/MaDaoGame-Web.git
cd MaDaoGame-Web-master

# 启动服务（需要 Java 17+）
mvn spring-boot:run

# 浏览器访问
http://localhost:8080
```

## 项目结构速览

```
src/main/java/com/madao/game/
├── MadaoGameApplication.java    # 启动入口
├── controller/
│   └── GameController.java      # 9 页面路由 + 5 AJAX API + 心跳
├── service/
│   └── GameService.java         # 房间管理 / 猜拳 / 8种行动 / 定时清理
├── entity/
│   ├── GameRoom.java            # 房间实体（状态机 + actionQueue）
│   └── Player.java              # 玩家实体（HP / 装备 / 位置）
├── dao/
│   ├── GameDao.java             # game_room 表 CRUD
│   └── PlayerDao.java           # player 表 CRUD
└── util/
    └── DBUtil.java              # 连接池 + 自动建表

src/main/resources/
├── templates/                   # 7 个 Thymeleaf 游戏页面
├── static/js/common.js          # 核心轮询 + SmartPoll 跳转逻辑
└── application.properties       # 端口/H2/HikariCP 配置
```

<style scoped>
.tech-stack table {
  width: 100%;
  display: table;
}
.tech-stack th {
  background: var(--vp-c-bg-soft);
}
</style>
