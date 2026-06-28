package com.madao.game.dao;

import com.madao.game.entity.GameRoom;
import com.madao.game.exception.DatabaseException;
import com.madao.game.util.DBUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;

/**
 * 游戏房间数据访问层 —— 对 game_room 表执行 CRUD 操作。
 *
 * <p>使用原生 JDBC（Statement + PreparedStatement），通过 {@link DBUtil} 获取连接。
 * 所有方法均使用 try-with-resources 确保连接自动释放。</p>
 *
 * <p>所属模块：DAO 层，被 {@code GameService} 调用，负责房间数据的持久化。</p>
 *
 * @author madao
 */
@Repository
public class GameDao {

    private static final Logger log = LoggerFactory.getLogger(GameDao.class);

    /**
     * 插入一条新的游戏房间记录。
     * @param room 包含完整房间信息的 GameRoom 对象
     */
    public void insert(GameRoom room) {
        String sql = "INSERT INTO game_room(id, player_count, status, round, phase, winner) VALUES(?,?,?,?,?,?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room.getId());            // UUID 主键
            ps.setInt(2, room.getPlayerCount());      // 最大玩家数
            ps.setString(3, room.getStatus());        // WAITING / PLAYING / FINISHED
            ps.setInt(4, room.getRound());            // 当前回合数
            ps.setString(5, room.getPhase());         // GUESS / ACTION
            ps.setString(6, room.getWinner());        // 胜利者（初始为 null）
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("插入房间记录失败", e);
        }
    }

    /**
     * 根据房间ID查询房间信息（不包含玩家列表）。
     * @param id 房间UUID
     * @return 找到返回 GameRoom 对象，未找到返回 null
     */
    public GameRoom findById(String id) {
        //SELECT * — 查询该行所有字段
        //FROM game_room — 从房间表查
        //WHERE id = ? — 按主键精确匹配
        //? 是占位符（参数化查询），值在稍后通过 setString 填入，防止 SQL 注入
        String sql = "SELECT * FROM game_room WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            //ps.executeQuery() → 执行 SELECT 语句，返回一个 结果集游标
            //ResultSet rs → 指向查询结果，初始时游标在第一行之前
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    //查到房间了
                GameRoom room = new GameRoom();
                room.setId(rs.getString("id"));
                room.setPlayerCount(rs.getInt("player_count"));
                room.setStatus(rs.getString("status"));
                room.setRound(rs.getInt("round"));
                room.setPhase(rs.getString("phase"));
                room.setWinner(rs.getString("winner"));
                return room;
            }
            }
        } catch (SQLException e) {
            throw new DatabaseException("查询房间记录失败", e);
        }
        return null;  // 未找到对应房间
    }

    /**
     * 更新房间状态信息（status、round、phase、winner）。
     * 通常在游戏状态切换时调用（如开始游戏、回合结束、游戏结束）。
     * @param room 包含最新状态的 GameRoom 对象
     */
    public void updateStatus(GameRoom room) {
        //更新 game_room 表
        //SET status=?	设置 status 列 = 第 1 个占位符的值
        //round=?	设置 round 列 = 第 2 个占位符的值
        //phase=?	设置 phase 列 = 第 3 个占位符的值
        //winner=?	设置 winner 列 = 第 4 个占位符的值
        //WHERE id=?	限定条件：只更新 id 匹配的房间（第 5 个占位符）
        String sql = "UPDATE game_room SET status=?, round=?, phase=?, winner=? WHERE id=?";
        try (Connection conn = DBUtil.getConnection();             // try-with-resources 自动关闭连接
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room.getStatus());                     // WAITING / PLAYING / FINISHED
            ps.setInt(2, room.getRound());                         // 当前回合数
            ps.setString(3, room.getPhase());                      // GUESS / ACTION
            ps.setString(4, room.getWinner());                     // 胜利者（可为 null）
            ps.setString(5, room.getId());                         // WHERE 条件：房间 UUID
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("更新房间状态失败", e);
        }
    }

    /**
     * 根据房间ID删除房间记录。
     * 注意：数据库有外键约束，需先删除 player 表相关记录。
     * @param id 房间UUID
     */
    public void deleteById(String id) {
        String sql = "DELETE FROM game_room WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();             // 从连接池获取连接
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);                                   // 房间 UUID
            ps.executeUpdate();                                    // 执行删除
        } catch (SQLException e) {
            throw new DatabaseException("删除房间记录失败", e);
        }
    }

    /**
     * 删除 game_room 表中的所有记录。
     * 用于服务器重启时清理遗留的孤儿数据（因为 rooms 是内存结构，重启后丢失）。
     * 注意：需确保 player 表先被清理以避免外键约束冲突。
     */
    public void deleteAll() {
        String sql = "DELETE FROM game_room";
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate(sql);
            log.info("已清理 game_room 表: {} 条记录", deleted);
        } catch (SQLException e) {
            throw new DatabaseException("清空房间表失败", e);
        }
    }
}