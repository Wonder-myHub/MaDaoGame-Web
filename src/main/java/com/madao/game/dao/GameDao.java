package com.madao.game.dao;

import com.madao.game.entity.GameRoom;
import com.madao.game.util.DBUtil;
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
            throw new RuntimeException("插入房间记录失败", e);
        }
    }

    /**
     * 根据房间ID查询房间信息（不包含玩家列表）。
     * @param id 房间UUID
     * @return 找到返回 GameRoom 对象，未找到返回 null
     */
    public GameRoom findById(String id) {
        String sql = "SELECT * FROM game_room WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
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
            throw new RuntimeException("查询房间记录失败", e);
        }
        return null;  // 未找到对应房间
    }

    /**
     * 更新房间状态信息（status、round、phase、winner）。
     * 通常在游戏状态切换时调用（如开始游戏、回合结束、游戏结束）。
     * @param room 包含最新状态的 GameRoom 对象
     */
    public void updateStatus(GameRoom room) {
        String sql = "UPDATE game_room SET status=?, round=?, phase=?, winner=? WHERE id=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room.getStatus());
            ps.setInt(2, room.getRound());
            ps.setString(3, room.getPhase());
            ps.setString(4, room.getWinner());
            ps.setString(5, room.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新房间状态失败", e);
        }
    }

    /**
     * 根据房间ID删除房间记录。
     * 注意：数据库有外键约束，需先删除 player 表相关记录。
     * @param id 房间UUID
     */
    public void deleteById(String id) {
        String sql = "DELETE FROM game_room WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除房间记录失败", e);
        }
    }
}