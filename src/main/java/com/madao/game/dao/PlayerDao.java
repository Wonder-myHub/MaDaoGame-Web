package com.madao.game.dao;

import com.madao.game.entity.Player;
import com.madao.game.util.DBUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩家数据访问层 —— 对 player 表执行 CRUD 操作。
 *
 * <p>使用原生 JDBC,通过 {@link DBUtil} 获取连接。每个玩家记录关联一个 room_id，
 * 同一房间的所有玩家可通过 room_id 批量查询。</p>
 *
 * <p>所属模块：DAO 层，被 {@code GameService} 和 {@code GameController} 调用，
 * 负责玩家数据的持久化和状态恢复（支持断线重连）。</p>
 *
 * @author madao
 */
@Repository
public class PlayerDao {

    private static final Logger log = LoggerFactory.getLogger(PlayerDao.class);

    /**
     * 插入一条新玩家记录。
     * last_activity 初始值设为当前时间，标记玩家在线。
     * @param player 包含完整信息的 Player 对象
     */
    public void insert(Player player) {
        String sql = "INSERT INTO player(id, room_id, name, hp, steps, horse, knife, buff, location, alive, guess, last_activity) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.getId());                     // UUID 主键
            ps.setString(2, player.getRoomId());                 // 外键：所属房间ID
            ps.setString(3, player.getName());                   // 玩家昵称
            ps.setInt(4, player.getHp());                        // 生命值，默认10
            ps.setInt(5, player.getSteps());                     // 步数，默认0
            ps.setBoolean(6, player.isHorse());                  // 是否拥有马
            ps.setBoolean(7, player.isKnife());                  // 是否拥有刀
            ps.setBoolean(8, player.isBuff());                   // 是否有血祭buff
            ps.setString(9, player.getLocation());               // 初始城市
            ps.setBoolean(10, player.isAlive());                 // 是否存活
            ps.setString(11, player.getGuess());                 // 猜拳手势（初始为null）
            // 如果已有 lastActivity 则使用，否则设为当前时间
            ps.setTimestamp(12, player.getLastActivity() != null
                    ? player.getLastActivity()
                    : new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入玩家记录失败", e);
        }
    }

    /**
     * 查询指定房间的所有玩家。
     * @param roomId 房间UUID
     * @return 该房间所有玩家的列表（可能为空）
     */
    public List<Player> findByRoomId(String roomId) {
        List<Player> list = new ArrayList<>();
        String sql = "SELECT * FROM player WHERE room_id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {                                    // 遍历所有结果
                Player p = new Player();
                p.setId(rs.getString("id"));
                p.setRoomId(rs.getString("room_id"));
                p.setName(rs.getString("name"));
                p.setHp(rs.getInt("hp"));
                p.setSteps(rs.getInt("steps"));
                p.setHorse(rs.getBoolean("horse"));
                p.setKnife(rs.getBoolean("knife"));
                p.setBuff(rs.getBoolean("buff"));
                p.setLocation(rs.getString("location"));
                p.setAlive(rs.getBoolean("alive"));
                p.setGuess(rs.getString("guess"));
                p.setLastActivity(rs.getTimestamp("last_activity"));
                list.add(p);
            }
            }
        } catch (SQLException e) {
            throw new RuntimeException("按房间ID查询玩家失败", e);
        }
        return list;
    }

    /**
     * 根据玩家ID查询单个玩家。
     * @param id 玩家UUID
     * @return 找到返回 Player 对象，未找到返回 null
     */
    public Player findById(String id) {
        String sql = "SELECT * FROM player WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {                                       // 最多一条记录
                Player p = new Player();
                p.setId(rs.getString("id"));
                p.setRoomId(rs.getString("room_id"));
                p.setName(rs.getString("name"));
                p.setHp(rs.getInt("hp"));
                p.setSteps(rs.getInt("steps"));
                p.setHorse(rs.getBoolean("horse"));
                p.setKnife(rs.getBoolean("knife"));
                p.setBuff(rs.getBoolean("buff"));
                p.setLocation(rs.getString("location"));
                p.setAlive(rs.getBoolean("alive"));
                p.setGuess(rs.getString("guess"));
                p.setLastActivity(rs.getTimestamp("last_activity"));
                return p;
            }
            }
        } catch (SQLException e) {
            throw new RuntimeException("按玩家ID查询失败", e);
        }
        return null;  // 未找到对应玩家
    }

    /**
     * 更新玩家状态（HP、步数、装备、位置、存活、猜拳手势、活动时间）。
     * 用于每次玩家执行操作后同步到数据库。
     * @param player 包含最新状态的 Player 对象
     */
    public void update(Player player) {
        String sql = "UPDATE player SET hp=?, steps=?, horse=?, knife=?, buff=?, " +
                "location=?, alive=?, guess=?, last_activity=? WHERE id=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getHp());                          // 最新生命值
            ps.setInt(2, player.getSteps());                       // 剩余步数
            ps.setBoolean(3, player.isHorse());                    // 装备状态
            ps.setBoolean(4, player.isKnife());
            ps.setBoolean(5, player.isBuff());
            ps.setString(6, player.getLocation());                 // 最新位置
            ps.setBoolean(7, player.isAlive());                    // 存活状态
            ps.setString(8, player.getGuess());                    // 猜拳手势
            ps.setTimestamp(9, player.getLastActivity() != null    // 最后活动时间
                    ? player.getLastActivity()
                    : new Timestamp(System.currentTimeMillis()));
            ps.setString(10, player.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新玩家状态失败", e);
        }
    }

    /**
     * 根据房间ID删除该房间所有玩家记录。
     * 通常在清理房间时调用。
     * @param roomId 房间UUID
     */
    public void deleteByRoomId(String roomId) {
        String sql = "DELETE FROM player WHERE room_id = ?";
        try (Connection conn = DBUtil.getConnection();             // 从连接池获取连接
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);                               // 房间 UUID，删除该房间所有玩家
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("按房间ID删除玩家失败", e);
        }
    }

    /**
     * 根据玩家ID删除单条玩家记录。
     * 用于玩家主动离开房间时清除其数据库记录。
     * @param playerId 玩家UUID
     */
    public void deleteById(String playerId) {
        String sql = "DELETE FROM player WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();             // 从连接池获取连接
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId);                             // 玩家 UUID 主键
            ps.executeUpdate();                                    // 执行删除
        } catch (SQLException e) {
            throw new RuntimeException("按玩家ID删除记录失败", e);
        }
    }

    /**
     * 删除 player 表中的所有记录。
     * 用于服务器重启时清理遗留的孤儿数据（因为 rooms 是内存结构，重启后丢失）。
     */
    public void deleteAll() {
        String sql = "DELETE FROM player";
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate(sql);
            log.info("已清理 player 表: {} 条记录", deleted);
        } catch (SQLException e) {
            throw new RuntimeException("清空玩家表失败", e);
        }
    }
}