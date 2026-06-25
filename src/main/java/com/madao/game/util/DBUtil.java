package com.madao.game.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.*;

/**
 * H2 数据库连接工具类 —— 管理数据库连接和自动建表。
 *
 * <p>使用 H2 嵌入式文件数据库（file 模式），通过 Spring Boot 自动配置的
 * HikariCP 连接池获取连接，避免频繁创建/销毁连接的开销。</p>
 *
 * <p>Spring 容器初始化后自动执行建表逻辑（CREATE TABLE IF NOT EXISTS），确保首次启动即可使用。</p>
 *
 * <h3>数据库表结构</h3>
 * <ul>
 *   <li><b>game_room</b> — 游戏房间表（id, player_count, status, round, phase, winner）</li>
 *   <li><b>player</b> — 玩家表（id, room_id外键, name, hp, steps, horse, knife, buff, location, alive, guess, last_activity）</li>
 * </ul>
 *
 * <h3>安全性说明</h3>
 * <p>所有用户输入的查询均通过 DAO 层的 {@link PreparedStatement} 参数化执行，杜绝 SQL 注入。
 * 建表 DDL 为硬编码静态 SQL，不含任何外部输入，无注入风险。</p>
 *
 * @author madao
 */
@Component
public class DBUtil {

    /** SLF4J 日志记录器，用于记录建表、连接异常等事件 */
    private static final Logger log = LoggerFactory.getLogger(DBUtil.class);

    /** Spring Boot 自动配置的 HikariCP 数据源（静态引用，供静态方法使用） */
    private static DataSource dataSource;

    /**
     * 通过 Setter 注入 DataSource（Spring Boot 自动配置的 HikariCP 连接池）。
     * 使用 @Autowired 方法注入而非字段注入，便于设置静态引用。
     */
    @Autowired
    public void setDataSource(DataSource ds) {
        DBUtil.dataSource = ds;
    }

    /**
     * Spring 容器初始化后自动建表。
     * 替代原有的 static 块中的 Class.forName + createTablesIfNotExist 逻辑。
     */
    @PostConstruct
    public void init() {
        createTablesIfNotExist();
    }

    /**
     * 创建数据库表（如果不存在）。
     *
     * <p>执行两条 DDL（均为硬编码常量，无 SQL 注入风险）：
     * <ol>
     *   <li>game_room 表 —— 主键 id（UUID），包含玩家数量、状态、回合、阶段、胜利者字段</li>
     *   <li>player 表 —— 主键 id（UUID），room_id 外键关联 game_room，包含玩家所有属性和最后活动时间</li>
     * </ol>
     * </p>
     */
    private void createTablesIfNotExist() {
        // 创建游戏房间表：存储房间基本状态信息（硬编码静态SQL，无注入风险）
        String createGameRoom = "CREATE TABLE IF NOT EXISTS game_room (" +
                "id VARCHAR(36) PRIMARY KEY, " +
                "player_count INT NOT NULL, " +
                "status VARCHAR(20) NOT NULL, " +
                "round INT DEFAULT 0, " +
                "phase VARCHAR(20) DEFAULT 'GUESS', " +
                "winner VARCHAR(50))";

        // 创建玩家表：存储每个玩家的详细状态（硬编码静态SQL，无注入风险）
        String createPlayer = "CREATE TABLE IF NOT EXISTS player (" +
                "id VARCHAR(36) PRIMARY KEY, " +
                "room_id VARCHAR(36) NOT NULL, " +
                "name VARCHAR(50) NOT NULL, " +
                "hp INT DEFAULT 10, " +
                "steps INT DEFAULT 0, " +
                "horse BOOLEAN DEFAULT FALSE, " +
                "knife BOOLEAN DEFAULT FALSE, " +
                "buff BOOLEAN DEFAULT FALSE, " +
                "location VARCHAR(30), " +
                "alive BOOLEAN DEFAULT TRUE, " +
                "guess VARCHAR(10), " +
                "last_activity TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY (room_id) REFERENCES game_room(id))";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createGameRoom);
            stmt.execute(createPlayer);
            log.info("数据库表已创建或已存在（HikariCP 连接池）");
        } catch (SQLException e) {
            throw new RuntimeException("数据库建表失败", e);
        }
    }

    /**
     * 从 HikariCP 连接池获取数据库连接。
     * @return 一个池化的 H2 数据库连接
     * @throws SQLException 连接失败时抛出
     */
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 安全关闭数据库资源（Connection、Statement、ResultSet）。
     * 每个资源独立 try-catch，某个关闭失败不影响其他资源的关闭。
     * 注意：通过连接池获取的 Connection，close() 会归还到池中而非真正关闭。
     *
     * @param conn 数据库连接（可为 null）
     * @param stmt Statement 对象（可为 null）
     * @param rs   ResultSet 对象（可为 null）
     */
    public static void close(Connection conn, Statement stmt, ResultSet rs) {
        try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
        try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}