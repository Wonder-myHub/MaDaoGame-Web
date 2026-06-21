package com.madao.game.util;

import java.sql.*;

/**
 * H2 数据库连接工具类 —— 管理数据库连接和自动建表。
 *
 * <p>使用 H2 嵌入式文件数据库（file 模式），文件存储在项目根目录 {@code ./madao_db}。
 * 开启 AUTO_SERVER 模式，允许多个进程同时访问同一数据库文件。</p>
 *
 * <p>类加载时自动执行建表逻辑（CREATE TABLE IF NOT EXISTS），确保首次启动即可使用。</p>
 *
 * <h3>数据库表结构</h3>
 * <ul>
 *   <li><b>game_room</b> — 游戏房间表（id, player_count, status, round, phase, winner）</li>
 *   <li><b>player</b> — 玩家表（id, room_id外键, name, hp, steps, horse, knife, buff, location, alive, guess, last_activity）</li>
 * </ul>
 *
 * @author madao
 */
public class DBUtil {

    /** H2 文件数据库连接URL，AUTO_SERVER=TRUE 允许多连接访问同一文件 */
    private static final String URL = "jdbc:h2:file:./madao_db;AUTO_SERVER=TRUE";

    /** H2 默认用户名 */
    private static final String USER = "sa";

    /** H2 默认空密码 */
    private static final String PASSWORD = "";

    /**
     * 静态初始化块：加载 H2 JDBC 驱动并自动建表。
     * 如果驱动加载失败则抛出 RuntimeException 终止启动。
     */
    static {
        try {
            Class.forName("org.h2.Driver");     // 加载 H2 JDBC 驱动
            createTablesIfNotExist();            // 自动创建表结构
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("H2 Driver not found", e);
        }
    }

    /**
     * 创建数据库表（如果不存在）。
     *
     * <p>执行两条 DDL：
     * <ol>
     *   <li>game_room 表 —— 主键 id（UUID），包含玩家数量、状态、回合、阶段、胜利者字段</li>
     *   <li>player 表 —— 主键 id（UUID），room_id 外键关联 game_room，包含玩家所有属性和最后活动时间</li>
     * </ol>
     * </p>
     */
    private static void createTablesIfNotExist() {
        // 创建游戏房间表：存储房间基本状态信息
        String createGameRoom = "CREATE TABLE IF NOT EXISTS game_room (" +
                "id VARCHAR(36) PRIMARY KEY, " +           // UUID 主键
                "player_count INT NOT NULL, " +            // 最大玩家数
                "status VARCHAR(20) NOT NULL, " +          // WAITING / PLAYING / FINISHED
                "round INT DEFAULT 0, " +                  // 当前回合数
                "phase VARCHAR(20) DEFAULT 'GUESS', " +    // GUESS / ACTION
                "winner VARCHAR(50))";                     // 胜利者名称

        // 创建玩家表：存储每个玩家的详细状态，外键关联 game_room
        String createPlayer = "CREATE TABLE IF NOT EXISTS player (" +
                "id VARCHAR(36) PRIMARY KEY, " +           // UUID 主键
                "room_id VARCHAR(36) NOT NULL, " +         // 外键：所属房间ID
                "name VARCHAR(50) NOT NULL, " +            // 玩家昵称
                "hp INT DEFAULT 10, " +                    // 生命值，默认10
                "steps INT DEFAULT 0, " +                  // 步数（行动点数）
                "horse BOOLEAN DEFAULT FALSE, " +          // 是否有马
                "knife BOOLEAN DEFAULT FALSE, " +          // 是否有刀
                "buff BOOLEAN DEFAULT FALSE, " +           // 是否有血祭buff
                "location VARCHAR(30), " +                 // 当前位置（city-N / outside）
                "alive BOOLEAN DEFAULT TRUE, " +           // 是否存活
                "guess VARCHAR(10), " +                    // 猜拳手势（石头/剪刀/布）
                "last_activity TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " + // 最后活动时间
                "FOREIGN KEY (room_id) REFERENCES game_room(id))";     // 外键约束

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute(createGameRoom);
            stmt.execute(createPlayer);
            System.out.println("数据库表已创建或已存在");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取数据库连接。
     * @return 一个新的 H2 数据库连接
     * @throws SQLException 连接失败时抛出
     */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /**
     * 安全关闭数据库资源（Connection、Statement、ResultSet）。
     * 每个资源独立 try-catch，某个关闭失败不影响其他资源的关闭。
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