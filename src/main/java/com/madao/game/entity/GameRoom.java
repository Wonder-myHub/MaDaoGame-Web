package com.madao.game.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 游戏房间实体类 —— 代表一个完整的游戏对局。
 *
 * <p>核心职责：维护房间状态、管理玩家列表、记录行动日志和聊天消息、控制行动队列轮转。</p>
 *
 * <h3>房间生命周期状态（status）</h3>
 * <ul>
 *   <li><b>WAITING</b> — 等待玩家加入，人满自动开始或房主强制开始</li>
 *   <li><b>PLAYING</b> — 游戏进行中</li>
 *   <li><b>FINISHED</b> — 游戏结束，已产生胜利者</li>
 * </ul>
 *
 * <h3>回合阶段（phase）</h3>
 * <ul>
 *   <li><b>GUESS</b> — 猜拳阶段，所有存活玩家出石头/剪刀/布</li>
 *   <li><b>ACTION</b> — 行动阶段，按步数随机顺序轮流执行操作</li>
 * </ul>
 *
 * <h3>线程安全说明</h3>
 * <p>玩家列表使用 {@link CopyOnWriteArrayList}，读写分离保证并发安全（适合读多写少场景）。
 * 关键写操作在 Service 层通过 synchronized 方法控制，避免竞态条件。</p>
 *
 * @author madao
 */
public class GameRoom {

    private static final Logger log = LoggerFactory.getLogger(GameRoom.class);

    /** 房间唯一标识（UUID），数据库主键 */
    private String id;

    /** 房间设定的最大玩家数 */
    private int playerCount;

    /** 房间状态：WAITING / PLAYING / FINISHED */
    private String status = "WAITING";

    /** 当前回合数，从1开始递增 */
    private int round = 0;

    /** 当前回合阶段：GUESS（猜拳）/ ACTION（行动） */
    private String phase = "GUESS";

    /** 游戏结束时的胜利者名称 */
    private String winner;

    /** 房间内所有玩家列表，使用 CopyOnWriteArrayList 保证读操作线程安全 */
    private List<Player> players = new CopyOnWriteArrayList<>();

    /** 行动队列：本回合有步数的玩家ID列表，已随机打乱顺序 */
    private List<String> actionQueue = new ArrayList<>();

    /** 行动队列当前指针：指向正在行动的玩家在 actionQueue 中的索引 */
    private int currentActionIndex = 0;

    /** 行动日志列表，最多保留30条，记录每回合所有玩家操作（CopyOnWriteArrayList 保证并发读安全） */
    private List<String> actionLogs = new CopyOnWriteArrayList<>();

    /** 聊天消息列表，最多保留50条（CopyOnWriteArrayList 保证并发读安全） */
    private List<String> chatMessages = new CopyOnWriteArrayList<>();

    // ==================================================================================
    //  日志管理
    // ==================================================================================

    /** @return 行动日志列表 */
    public List<String> getActionLogs() { return actionLogs; }
    public void setActionLogs(List<String> actionLogs) { this.actionLogs = actionLogs; }

    /**
     * 添加一条行动日志（同时输出到控制台）。
     * 当日志超过30条时，自动删除最旧的一条。
     */
    public void addLog(String log) {
        System.out.println(log);           // 同步输出到控制台，方便调试
        GameRoom.log.info(log);            // SLF4J 结构化日志
        actionLogs.add(log);
        if (actionLogs.size() > 30) actionLogs.remove(0); // 只保留最近30条
    }

    /**
     * 静默添加一条行动日志（不输出到控制台，用于批量日志如猜拳结果）。
     */
    public void addLogQuiet(String log) {
        actionLogs.add(log);
        if (actionLogs.size() > 30) actionLogs.remove(0);
    }

    // ==================================================================================
    //  聊天管理
    // ==================================================================================

    /** @return 聊天消息列表 */
    public List<String> getChatMessages() { return chatMessages; }
    public void setChatMessages(List<String> chatMessages) { this.chatMessages = chatMessages; }

    /**
     * 添加一条聊天消息，超过50条时自动删除最旧的一条。
     */
    public void addChatMessage(String msg) {
        chatMessages.add(msg);
        if (chatMessages.size() > 50) chatMessages.remove(0);
    }

    // ==================================================================================
    //  玩家状态查询
    // ==================================================================================

    /**
     * 获取房间内所有存活玩家列表。
     * @return 包含所有 isAlive()==true 的 Player 对象
     */
    public List<Player> getAlivePlayers() {
        List<Player> alive = new ArrayList<>();
        for (Player p : players) {
            if (p.isAlive()) alive.add(p);
        }
        return alive;
    }

    /**
     * 判断指定玩家是否是当前行动者。
     * @param playerId 玩家ID
     * @return true=轮到该玩家行动
     */
    public boolean isCurrentPlayer(String playerId) {
        if (actionQueue.isEmpty()) return false;                // 行动队列为空，无人可行动
        return actionQueue.get(currentActionIndex).equals(playerId);
    }

    /**
     * 轮到下一个玩家行动。
     * 如果当前玩家是队列最后一位，则清空队列（本回合行动阶段结束）。
     */
    public void nextPlayer() {
        if (currentActionIndex < actionQueue.size() - 1) {
            currentActionIndex++;           // 还有玩家未行动，指针后移
        } else {
            actionQueue.clear();            // 所有玩家已行动完毕，清空队列
            currentActionIndex = 0;
        }
    }

    /**
     * 获取尚未猜拳的玩家名称列表（用于前端显示等待信息）。
     * @return 所有 guess==null 的存活玩家名称
     */
    public List<String> getPlayersNotGuessed() {
        List<String> names = new ArrayList<>();
        for (Player p : getAlivePlayers()) {
            if (p.getGuess() == null) names.add(p.getName());   // 尚未出拳
        }
        return names;
    }

    /**
     * 获取当前轮到的行动者名称（用于前端显示）。
     * @return 当前行动玩家名称，队列为空时返回 null
     */
    public String getCurrentActionPlayerName() {
        if (actionQueue.isEmpty()) return null;
        String pid = actionQueue.get(currentActionIndex);       // 获取当前行动者ID
        for (Player p : players) {
            if (p.getId().equals(pid)) return p.getName();
        }
        return null;
    }

    /**
     * 获取当前行动者的剩余步数（用于前端显示）。
     * @return 剩余步数，队列为空时返回 0
     */
    public int getCurrentActionPlayerSteps() {
        if (actionQueue.isEmpty()) return 0;
        String pid = actionQueue.get(currentActionIndex);
        for (Player p : players) {
            if (p.getId().equals(pid)) return p.getSteps();
        }
        return 0;
    }

    // ==================================================================================
    //  Getter / Setter
    // ==================================================================================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }

    /** @return 房间状态：WAITING / PLAYING / FINISHED */
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }

    /** @return 当前阶段：GUESS / ACTION */
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }

    public List<Player> getPlayers() { return players; }
    /** 设置玩家列表时自动包装为 CopyOnWriteArrayList，确保线程安全 */
    public void setPlayers(List<Player> players) {
        this.players = players instanceof CopyOnWriteArrayList
                ? players : new CopyOnWriteArrayList<>(players);
    }

    public List<String> getActionQueue() { return actionQueue; }
    public void setActionQueue(List<String> actionQueue) { this.actionQueue = actionQueue; }

    public int getCurrentActionIndex() { return currentActionIndex; }
    public void setCurrentActionIndex(int currentActionIndex) { this.currentActionIndex = currentActionIndex; }
}