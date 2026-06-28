# 快速开始

本指南帮助你快速搭建 MaDaoGame 本地开发环境并启动游戏。

## 环境要求

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17 或更高 | Spring Boot 3.x 需要 Java 17+ |
| Maven | 3.6+ | 项目构建和依赖管理 |
| 浏览器 | 现代浏览器 | Chrome / Firefox / Edge |

## 项目获取

```bash
git clone https://github.com/Wonder-myHub/MaDaoGame-Web.git
cd MaDaoGame-Web-master
```

## 项目构建

```bash
# 完整构建（编译 + 测试 + 打包）
mvn clean package

# 跳过测试快速构建
mvn clean package -DskipTests
```

## 启动游戏

### 方式一：Maven 直接运行

```bash
mvn spring-boot:run
```

### 方式二：运行 JAR 包

```bash
java -jar target/madao-game-*.jar
```

### 方式三：IDE 中运行

在 IntelliJ IDEA 中直接运行 `MadaoGameApplication.java` 的 `main` 方法。

## 访问游戏

启动成功后，打开浏览器访问：

> **http://localhost:8080**

你将看到游戏首页，可以创建或加入房间。

## 配置文件说明

核心配置文件 `src/main/resources/application.properties`：

```properties
# 服务端口
server.port=8080

# H2 数据库（文件模式，数据持久化到 madao_db.mv.db）
spring.datasource.url=jdbc:h2:file:./madao_db

# HikariCP 连接池
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.maximum-pool-size=10
```

## 快速体验流程

1. **创建房间**：访问首页，输入玩家数量（2-4人）和你的名字，点击「创建房间」
2. **加入房间**：其他玩家输入房间号和名字，点击「加入房间」
3. **等待开始**：人满自动开始，或房主点击「强制开始」
4. **猜拳对局**：每人选择石头、剪刀或布
5. **行动阶段**：胜者消耗步数执行行动（移动/攻击/购买装备）
6. **决出胜者**：最后存活的玩家获胜

## 下一步

- 查看 [游戏规则](/guide/game-rules) 了解详细玩法
- 查看 [架构概览](/architecture/overview) 了解系统设计
- 查看 [API 参考](/api/reference) 了解接口详情
