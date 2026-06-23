package com.madao.game.service;

import com.madao.game.dao.GameDao;
import com.madao.game.dao.PlayerDao;
import com.madao.game.entity.GameRoom;
import com.madao.game.entity.Player;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 游戏核心服务 —— 管理所有游戏房间和游戏逻辑。
 *
 * <h2>职责概述</h2>
 * <ol>
 *   <li><b>房间管理</b>：创建房间、加入/重连、强制开始、获取房间状态</li>
 *   <li><b>猜拳系统</b>：收集玩家出拳 → 判定胜负 → 分配步数 → 构建行动队列</li>
 *   <li><b>行动系统</b>：8种行动（放弃/移动/买马/买刀/踢/刺/血祭/撵入）的执行与校验</li>
 *   <li><b>生命周期</b>：回合循环（GUESS → ACTION → GUESS）、游戏结束判定、房间清理</li>
 * </ol>
 *
 * <h2>游戏规则核心算法</h2>
 * <h3>猜拳阶段 (GUESS)</h3>
 * <p>所有存活玩家选择石头/剪刀/布 → 统计全部出完后，去重检查：</p>
 * <ul>
 *   <li>只有 1 种手势 → 平局，无人得步数</li>
 *   <li>恰好 2 种手势 → 判定胜负关系（石头赢剪刀、剪刀赢布、布赢石头），胜方每人步数 = 败方人数</li>
 *   <li>3 种手势都有 → 平局，无人得步数</li>
 * </ul>
 *
 * <h3>行动阶段 (ACTION)</h3>
 * <p>有步数的玩家按随机顺序轮流行动，每次消耗1步。可选操作：</p>
 * <table border="1">
 *   <tr><th>编号</th><th>操作</th><th>条件</th><th>效果</th></tr>
 *   <tr><td>0</td><td>放弃</td><td>无</td><td>不消耗步数</td></tr>
 *   <tr><td>1</td><td>移动</td><td>城内↔城外/换城</td><td>改变位置</td></tr>
 *   <tr><td>2</td><td>买马</td><td>未拥有马</td><td>获得马（解锁"踢"）</td></tr>
 *   <tr><td>3</td><td>买刀</td><td>未拥有刀</td><td>获得刀（解锁"刺"）</td></tr>
 *   <tr><td>4</td><td>踢</td><td>在城内+有马+同城有他人</td><td>伤害3（有buff×2=6），目标被踢到城外</td></tr>
 *   <tr><td>5</td><td>刺</td><td>有刀+同位置有他人</td><td>伤害1（有buff×2=2）</td></tr>
 *   <tr><td>6</td><td>血祭</td><td>无buff且HP>1</td><td>HP减半，获得buff（下次攻击伤害翻倍）</td></tr>
 *   <tr><td>7</td><td>撵入</td><td>在城外+同位置有他人</td><td>将目标强制送入指定城市</td></tr>
 * </table>
 *
 * <h2>并发控制</h2>
 * <ul>
 *   <li>房间容器使用 {@link ConcurrentHashMap} 保证并发读写安全</li>
 *   <li>关键写操作（加入/猜拳/行动/离开）使用 {@link ConcurrentHashMap#compute} 实现<b>按房间粒度的细粒度锁</b></li>
 *   <li>不同房间的操作完全并行，同一房间的操作在 compute 回调中串行化</li>
 * </ul>
 *
 * <h2>清理策略</h2>
 * <ul>
 *   <li><b>主动离开</b>：lastActivity 设为 0（epoch），全部离开后立即清理</li>
 *   <li><b>定时清理</b>：@Scheduled 每60秒扫描，清理所有玩家2分钟无活动的房间</li>
 *   <li><b>清理内容</b>：player 表 → game_room 表 → 内存缓存，保证级联删除</li>
 * </ul>
 *
 * <h2>数据一致性</h2>
 * <p>玩家状态有两份副本：内存（GameRoom.players）和数据库（player 表）。
 * 每次操作<b>先更新 DB，再同步内存</b>，确保数据持久化优先。</p>
 *
 * @author madao
 */
@Service
public class GameService {

    /** 内存房间缓存，key=房间UUID，使用 ConcurrentHashMap 保证并发读安全 */
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();

    /** 玩家数据访问对象，读写玩家 HP/步数/装备/位置等属性变化 */
    @Autowired
    private PlayerDao playerDao;

    /** 房间数据访问对象，读写房间状态/回合/阶段变化 */
    @Autowired
    private GameDao gameDao;

    // ==================================================================================
    //  房间生命周期管理
    // ==================================================================================

    /**
     * 创建一个新游戏房间。
     *
     * <p>流程：生成UUID → 初始化房间对象 → 持久化到数据库 → 放入内存缓存。</p>
     *
     * @param playerCount 最大玩家数（至少2人）
     * @return 新房间的 UUID
     */
    public String createRoom(int playerCount) {
        GameRoom room = new GameRoom();
        room.setId(UUID.randomUUID().toString());           // 生成唯一房间ID
        room.setPlayerCount(playerCount);
        room.setStatus("WAITING");                           // 初始状态：等待玩家加入
        gameDao.insert(room);                                // 持久化到 game_room 表
        rooms.put(room.getId(), room);                       // 放入内存缓存
        logToConsole("房间创建：" + room.getId());
        return room.getId();
    }

    /**
     * 玩家加入房间（支持断线重连）。
     *
     * <h3>加入逻辑（按房间状态分两路）</h3>
     *
     * <p><b>WAITING 状态</b></p>
     * <ol>
     *   <li>检查房间内是否有同名玩家 → 存在且离线则重连；存在且在线则拒绝</li>
     *   <li>检查玩家是否主动退出过（lastActivity == 0）→ 拒绝重连</li>
     *   <li>检查房间是否已满 → 拒绝加入</li>
     *   <li>创建新玩家，分配城市（city-N），持久化到DB和内存</li>
     *   <li>人满自动开始游戏</li>
     * </ol>
     *
     * <p><b>PLAYING / FINISHED 状态</b></p>
     * <ol>
     *   <li>仅允许断线重连（同名玩家必须存在且离线）</li>
     *   <li>拒绝新玩家加入进行中的房间</li>
     * </ol>
     *
     * @param roomId     房间UUID
     * @param playerName 玩家昵称（trim后使用）
     * @return 玩家UUID（新玩家或重连玩家的ID）
     * @throws RuntimeException 房间不存在/已满/昵称冲突/已主动退出
     */
    public String joinRoom(String roomId, String playerName) {
        // 使用 AtomicReference 传递 compute 闭包内的返回值
        java.util.concurrent.atomic.AtomicReference<String> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        rooms.compute(roomId, (id, room) -> {
            if (room == null) throw new RuntimeException("房间不存在");

            // ---------- 查找房间内是否已有同名玩家（用于重连判断） ----------
            // 同时检查内存和DB中的玩家状态
            Player existingPlayerInMemory = null;
            for (Player p : room.getPlayers()) {
                if (p.getName().equals(playerName)) {
                    existingPlayerInMemory = p;
                    break;
                }
            }
            // 从DB重新加载，确保 lastActivity 是最新的（避免内存中的过期数据）
            Player existingPlayer = existingPlayerInMemory != null
                    ? playerDao.findById(existingPlayerInMemory.getId())
                    : null;

            // ========== WAITING 状态：等待中，可新加入 ==========
            if ("WAITING".equals(room.getStatus())) {
                // --- 重连分支：同名玩家已存在 ---
                if (existingPlayer != null) {
                    // 如果同名玩家在线（5秒内有活动），拒绝新玩家加入
                    if (isPlayerOnline(existingPlayer)) {
                        throw new RuntimeException("该昵称的玩家当前在线，无法加入");
                    }
                    // lastActivity==0 表示玩家主动离开了，不允许重连
                    if (existingPlayer.getLastActivity() != null && existingPlayer.getLastActivity().getTime() == 0) {
                        throw new RuntimeException("该昵称的玩家已主动退出，无法重新加入");
                    }
                    // 更新在线时间，同步到DB和内存 → 完成重连
                    existingPlayer.setLastActivity(new Timestamp(System.currentTimeMillis()));
                    playerDao.update(existingPlayer);
                    if (existingPlayerInMemory != null) {
                        existingPlayerInMemory.setLastActivity(existingPlayer.getLastActivity());
                    }
                    room.addLog(playerName + " 重新连接");
                    logToConsole(playerName + " 重新连接");
                    resultRef.set(existingPlayer.getId());
                    return room;
                }

                // --- 新玩家分支：确保没有同名玩家 ---
                // 再次检查内存中的同名玩家（防止并发场景下 existingPlayer 从 DB 查不到但内存中已存在）
                for (Player p : room.getPlayers()) {
                    if (p.getName().equals(playerName)) {
                        throw new RuntimeException("该昵称已被使用，请更换昵称");
                    }
                }

                if (room.getPlayers().size() >= room.getPlayerCount()) {
                    throw new RuntimeException("房间已满");
                }

                // 创建新玩家对象
                Player player = new Player();
                player.setId(UUID.randomUUID().toString());
                player.setRoomId(roomId);
                player.setName(playerName);
                player.setHp(10);                                // 初始生命值10
                player.setLocation("city-" + (room.getPlayers().size() + 1)); // 每个玩家分配不同城市
                player.setAlive(true);
                player.setLastActivity(new Timestamp(System.currentTimeMillis()));
                playerDao.insert(player);                        // 先持久化
                room.getPlayers().add(player);                   // 再同步内存（CopyOnWriteArrayList 线程安全）

                room.addLog(playerName + " 加入了房间");
                logToConsole(playerName + " 加入了房间");

                // 人满自动开始游戏
                if (room.getPlayers().size() == room.getPlayerCount()) {
                    startGame(room);
                }
                resultRef.set(player.getId());
                return room;
            }

            // ========== PLAYING / FINISHED 状态：游戏中，仅允许重连 ==========
            if ("PLAYING".equals(room.getStatus()) || "FINISHED".equals(room.getStatus())) {
                if (existingPlayer == null) {
                    throw new RuntimeException("该昵称不在房间中，无法重新加入"); // 杜绝新玩家加入进行中的游戏
                }
                if (isPlayerOnline(existingPlayer)) {
                    throw new RuntimeException("该昵称的玩家当前在线，无法加入");
                }
                if (existingPlayer.getLastActivity() != null && existingPlayer.getLastActivity().getTime() == 0) {
                    throw new RuntimeException("您已主动退出，无法重新加入");
                }
                // 重连：更新在线时间，同步到DB和内存
                existingPlayer.setLastActivity(new Timestamp(System.currentTimeMillis()));
                playerDao.update(existingPlayer);
                if (existingPlayerInMemory != null) {
                    existingPlayerInMemory.setLastActivity(existingPlayer.getLastActivity());
                }
                room.addLog(existingPlayer.getName() + " 重新连接");
                logToConsole(existingPlayer.getName() + " 重新连接");
                resultRef.set(existingPlayer.getId());
                return room;
            }

            throw new RuntimeException("房间状态异常");
        });

        return resultRef.get();
    }

    /**
     * 判断玩家是否在线。
     *
     * <p>判定规则：
     * <ul>
     *   <li>lastActivity 为 null → 离线</li>
     *   <li>lastActivity.getTime() == 0 → 主动退出，离线</li>
     *   <li>距上次活动超过 5 秒 → 判定为离线</li>
     * </ul>
     *
     * @param player 待判定的玩家（需从DB加载，确保 lastActivity 最新）
     * @return true=在线（5秒内有活动）
     */
    public boolean isPlayerOnline(Player player) {
        Timestamp last = player.getLastActivity();
        if (last == null) return false;                      // 从未活动过
        if (last.getTime() == 0) return false;               // 主动退出标记
        return (System.currentTimeMillis() - last.getTime()) < 5000; // 5秒内有活动
    }

    /**
     * 开始游戏 —— 将房间状态从 WAITING 切换到 PLAYING。
     * 设置回合为1、阶段为 GUESS、持久化到数据库。
     */
    private void startGame(GameRoom room) {
        room.setStatus("PLAYING");
        room.setRound(1);                                    // 第1回合
        room.setPhase("GUESS");                              // 从猜拳阶段开始
        gameDao.updateStatus(room);                          // 持久化状态变更
        room.addLog("游戏开始，第 " + room.getRound() + " 回合");
        logToConsole("游戏开始，第 " + room.getRound() + " 回合");
    }

    /**
     * 房主强制开始游戏（不等凑满人）。
     *
     * <p>前置条件：
     * <ul>
     *   <li>房间状态必须为 WAITING</li>
     *   <li>调用者必须是第一个加入的玩家（房主）</li>
     *   <li>房间内至少2人</li>
     * </ul>
     *
     * @param roomId   房间UUID
     * @param playerId 调用者玩家UUID（必须是房主）
     * @throws RuntimeException 条件不满足时抛出
     */
    public void forceStartGame(String roomId, String playerId) {
        rooms.compute(roomId, (id, room) -> {
            if (room == null) throw new RuntimeException("房间不存在");
            if (!"WAITING".equals(room.getStatus())) throw new RuntimeException("游戏已经开始或已结束");
            // 第一个加入的玩家即为房主
            if (room.getPlayers().isEmpty() || !room.getPlayers().get(0).getId().equals(playerId)) {
                throw new RuntimeException("只有房主才能开始游戏");
            }
            if (room.getPlayers().size() < 2) throw new RuntimeException("当前人数不足，至少需要2人");

            Player host = playerDao.findById(playerId);
            String hostName = host != null ? host.getName() : playerId;
            room.addLog(hostName + " 强制开始游戏");
            logToConsole(hostName + " 强制开始游戏");

            startGame(room);
            return room;
        });
    }

    /**
     * 获取房间信息（带缓存和数据库回退）。
     *
     * <p>查询顺序：内存缓存 → 数据库 → 加载玩家列表 → 回写缓存。</p>
     *
     * @param roomId 房间UUID
     * @return 房间对象（含玩家列表），不存在返回 null
     */
    public GameRoom getRoom(String roomId) {
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            // 缓存未命中，尝试从数据库恢复（如服务重启后）
            room = gameDao.findById(roomId);
            if (room != null) {
                List<Player> players = playerDao.findByRoomId(roomId); // 加载该房间所有玩家
                room.setPlayers(players);
                rooms.put(roomId, room);                       // 回写内存缓存
            }
        }
        return room;
    }

    // ==================================================================================
    //  猜拳系统
    // ==================================================================================

    /**
     * 玩家提交猜拳手势。
     *
     * <p>将玩家猜拳结果同步到 DB 和内存，检查是否所有存活玩家都已出拳，
     * 如果全部完成则调用 {@link #processGuessResult} 执行猜拳判定。</p>
     *
     * @param playerId 玩家UUID
     * @param gesture  手势："石头" / "剪刀" / "布"
     */
    public void submitGuess(String playerId, String gesture) {
        Player player = playerDao.findById(playerId);
        if (player == null || !player.isAlive()) return;     // 无效玩家或已死亡，忽略

        String roomId = player.getRoomId();
        rooms.compute(roomId, (id, room) -> {
            if (room == null) return null;

            // 先更新数据库
            player.setGuess(gesture);
            playerDao.update(player);

            // 再同步内存中的玩家状态
            for (Player p : room.getPlayers()) {
                if (p.getId().equals(playerId)) {
                    p.setGuess(gesture);
                    break;
                }
            }

            logToConsole(player.getName() + " 出了 " + gesture);

            // 检查是否所有存活玩家都已出拳
            List<Player> alive = room.getAlivePlayers();
            boolean allGuessed = alive.stream().allMatch(p -> p.getGuess() != null);
            if (allGuessed) {
                processGuessResult(room);                        // 全体出完 → 执行判定
            }
            return room;
        });
    }

    /**
     * 处理猜拳结果 —— 猜拳系统的核心算法。
     *
     * <h3>算法流程</h3>
     * <ol>
     *   <li>收集所有存活玩家的出拳选择</li>
     *   <li>去重统计手势种类数：
     *     <ul>
     *       <li>种类数 ≠ 2 → 平局，无人得步数，回到 GUESS 阶段</li>
     *       <li>种类数 == 2 → 判定胜负关系，胜者步数 = 败者人数</li>
     *     </ul>
     *   </li>
     *   <li>胜者每人获得步数后，按随机顺序构建行动队列，切换到 ACTION 阶段</li>
     *   <li>胜者人数为0（极端情况）→ 也回到 GUESS 阶段</li>
     * </ol>
     *
     * <h3>胜负判定规则</h3>
     * <p>石头 赢 剪刀，剪刀 赢 布，布 赢 石头。</p>
     *
     * @param room 游戏房间
     */
    private void processGuessResult(GameRoom room) {
        List<Player> alive = room.getAlivePlayers();          // 当前存活玩家列表

        // 收集每个玩家的出拳选择
        Map<Player, String> choices = new HashMap<>();
        for (Player p : alive) {
            choices.put(p, p.getGuess());
        }

        // 静默记录所有玩家出拳（不输出到控制台，避免刷屏）
        for (Player p : alive) {
            room.addLogQuiet(p.getName() + " 出了 " + choices.get(p));
        }

        // ---------- 去重统计：判断是否为有效对决 ----------
        Set<String> uniqueGestures = new HashSet<>(choices.values());
        if (uniqueGestures.size() != 2) {
            // 平局判定：size=1 表示全部相同手势，size=3 表示三种手势各有一人
            // 两种情况均视为平局，不分配步数
            alive.forEach(p -> {
                p.setGuess(null);                            // 清除出拳记录
                p.setSteps(0);                               // 步数归零
                playerDao.update(p);
            });
            room.setRound(room.getRound() + 1);              // 平局也算一个回合
            room.addLog("第 " + (room.getRound() - 1) + " 回合猜拳平局，无人获得步数");
            room.addLog("第 " + (room.getRound() - 1) + " 回合结束，进入第 " + room.getRound() + " 回合猜拳");
            logToConsole("第 " + (room.getRound() - 1) + " 回合猜拳平局，无人获得步数");
            logToConsole("第 " + (room.getRound() - 1) + " 回合结束，进入第 " + room.getRound() + " 回合猜拳");
            room.setPhase("GUESS");                          // 重新猜拳
            gameDao.updateStatus(room);
            return;
        }

        // ---------- 判定胜负关系 ----------
        // 恰好两种手势，判定哪个是胜势(dominant)，哪个是败势(suppressed)
        final String dominant, suppressed;
        String[] two = uniqueGestures.toArray(new String[0]);
        if ((two[0].equals("石头") && two[1].equals("剪刀")) ||
                (two[0].equals("剪刀") && two[1].equals("石头"))) {
            dominant = "石头"; suppressed = "剪刀";           // 石头 克 剪刀
        } else if ((two[0].equals("剪刀") && two[1].equals("布")) ||
                (two[0].equals("布") && two[1].equals("剪刀"))) {
            dominant = "剪刀"; suppressed = "布";             // 剪刀 克 布
        } else {
            dominant = "布"; suppressed = "石头";             // 布 克 石头
        }

        // 统计败方人数（胜者每人获得的步数 = 败方人数）
        int suppressedCount = (int) choices.values().stream()
                .filter(g -> g.equals(suppressed)).count();

        // ---------- 分配步数 ----------
        for (Player p : alive) {
            p.setGuess(null);                                // 清除出拳记录
            // 胜者获得步数=败方人数，败者步数=0
            p.setSteps(choices.get(p).equals(dominant) ? suppressedCount : 0);
            playerDao.update(p);
            if (p.getSteps() > 0) {
                room.addLog(p.getName() + " 获得 " + p.getSteps() + " 步");
                logToConsole(p.getName() + " 获得 " + p.getSteps() + " 步");
            }
        }

        // ---------- 构建行动队列 ----------
        // 因为 uniqueGestures.size()==2 已保证有败方，suppressedCount≥1，playersWithSteps 不可能为空
        List<Player> playersWithSteps = alive.stream()
                .filter(p -> p.getSteps() > 0)               // 筛选有步数的玩家
                .collect(Collectors.toList());

        // 随机打乱有步数玩家的行动顺序，保证公平
        Collections.shuffle(playersWithSteps);
        room.setActionQueue(playersWithSteps.stream()
                .map(Player::getId).collect(Collectors.toList())); // 存储玩家ID列表
        room.setCurrentActionIndex(0);                       // 从第0位开始
        room.setPhase("ACTION");                             // 切换到行动阶段
        gameDao.updateStatus(room);
        room.addLog("行动阶段开始，顺序：" +
                playersWithSteps.stream().map(Player::getName).collect(Collectors.toList()));
        logToConsole("行动阶段开始，顺序：" +
                playersWithSteps.stream().map(Player::getName).collect(Collectors.toList()));
    }

    // ==================================================================================
    //  行动系统（8种操作）
    // ==================================================================================

    /**
     * 执行一次玩家行动。
     *
     * <p>行动消耗1步，步数归零后自动切换到下一个玩家。全部玩家行动完毕则结束回合。</p>
     *
     * <h3>双副本同步策略</h3>
     * <p>玩家状态有 DB（dbPlayer）和内存（memPlayer）两份：
     * 操作同时修改两份副本，确保一致性。涉及目标玩家时仅更新 DB。</p>
     *
     * @param playerId   行动者UUID
     * @param actionType 行动类型（0-7）
     * @param targetId   目标玩家UUID（攻击类操作需要）
     * @param cityName   目标城市名（移动/撵入操作需要）
     * @return 操作结果描述
     */
    public String executeAction(String playerId, int actionType,
                                 String targetId, String cityName) {
        Player dbPlayer = playerDao.findById(playerId);      // 数据库副本
        if (dbPlayer == null) return "玩家不存在";
        String roomId = dbPlayer.getRoomId();

        java.util.concurrent.atomic.AtomicReference<String> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        rooms.compute(roomId, (id, room) -> {
            if (room == null) {
                resultRef.set("房间不存在");
                return null;
            }
            if (dbPlayer.getSteps() <= 0) {
                resultRef.set("没有步数");
                return room;
            }

            // 获取内存中的玩家副本
            Player memPlayer = null;
            for (Player p : room.getPlayers()) {
                if (p.getId().equals(playerId)) {
                    memPlayer = p;
                    break;
                }
            }
            if (memPlayer == null) {
                resultRef.set("玩家不在房间中");
                return room;
            }

            boolean actionDone = false;                          // 标记行动是否有效执行
            String logMsg = "";

            switch (actionType) {
                // ---- 0. 放弃行动 ----
                case 0:
                    actionDone = true;
                    logMsg = dbPlayer.getName() + " 放弃行动";
                    break;

                // ---- 1. 移动：城内 ↔ 城外 / 城市间穿梭 ----
                case 1:
                    if (dbPlayer.getLocation().startsWith("city")) {
                        // 当前在城内 → 移动到城外
                        dbPlayer.setLocation("outside");
                        memPlayer.setLocation("outside");
                        logMsg = dbPlayer.getName() + " 移动到城外";
                    } else {
                        // 当前在城外 → 必须选择一个目标城市
                        if (cityName == null || cityName.isEmpty()) {
                            resultRef.set("请选择城市");
                            return room;
                        }
                        dbPlayer.setLocation(cityName);
                        memPlayer.setLocation(cityName);
                        logMsg = dbPlayer.getName() + " 移动到 " + cityName;
                    }
                    actionDone = true;
                    break;

                // ---- 2. 购买马匹（解锁"踢"技能） ----
                case 2:
                    if (!dbPlayer.isHorse()) {                   // 只能买一次
                        dbPlayer.setHorse(true);
                        memPlayer.setHorse(true);
                        logMsg = dbPlayer.getName() + " 购买了一匹马";
                        actionDone = true;
                    }
                    break;

                // ---- 3. 购买刀（解锁"刺"技能） ----
                case 3:
                    if (!dbPlayer.isKnife()) {                   // 只能买一次
                        dbPlayer.setKnife(true);
                        memPlayer.setKnife(true);
                        logMsg = dbPlayer.getName() + " 购买了一把刀";
                        actionDone = true;
                    }
                    break;

                // ---- 4. 踢：骑在马上的城内攻击 ----
                //       条件：攻击者在城内 + 有马 + 同城有目标
                //       伤害：3点（有buff翻倍=6），目标被踢到城外
                case 4:
                    if (!dbPlayer.getLocation().startsWith("city") || !dbPlayer.isHorse()) break;
                    if (targetId == null) {
                        resultRef.set("请选择目标");
                        return room;
                    }
                    Player target4 = findTargetInSameLocation(dbPlayer, room, targetId); // 查找同城目标
                    if (target4 != null) {
                        int dmg = memPlayer.isBuff() ? 6 : 3;    // buff翻倍伤害
                        target4.setHp(target4.getHp() - dmg);
                        target4.setLocation("outside");          // 目标被踢到城外
                        dbPlayer.setBuff(false);                 // 消耗buff
                        memPlayer.setBuff(false);
                        playerDao.update(target4);               // 先持久化目标状态
                        logMsg = dbPlayer.getName() + " 踢了 " + target4.getName() + "，造成 " + dmg + " 点伤害";
                        if (target4.getHp() <= 0) {              // HP归零 → 死亡
                            target4.setAlive(false);
                            playerDao.update(target4);
                            room.addLog(target4.getName() + " 被踢出局！");
                            logToConsole(target4.getName() + " 被踢出局！");
                            checkGameEnd(room);                  // 检查是否游戏结束
                        }
                        actionDone = true;
                    }
                    break;

                // ---- 5. 刺：持刀攻击，不限位置（只要同位置） ----
                //       条件：攻击者有刀 + 同位置有目标
                //       伤害：1点（有buff翻倍=2）
                case 5:
                    if (!dbPlayer.isKnife()) break;              // 必须有刀
                    if (targetId == null) {
                        resultRef.set("请选择目标");
                        return room;
                    }
                    Player target5 = findTargetInSameLocation(dbPlayer, room, targetId);
                    if (target5 != null) {
                        int dmg = memPlayer.isBuff() ? 2 : 1;    // buff翻倍伤害
                        target5.setHp(target5.getHp() - dmg);
                        dbPlayer.setBuff(false);                 // 消耗buff
                        memPlayer.setBuff(false);
                        logMsg = dbPlayer.getName() + " 刺了 " + target5.getName() + "，造成 " + dmg + " 点伤害";
                        if (target5.getHp() <= 0) {              // HP归零 → 先设死亡再持久化，保证一致
                            target5.setAlive(false);
                        }
                        playerDao.update(target5);
                        if (target5.getHp() <= 0) {
                            room.addLog(target5.getName() + " 被刺死！");
                            logToConsole(target5.getName() + " 被刺死！");
                            checkGameEnd(room);
                        }
                        actionDone = true;
                    }
                    break;

                // ---- 6. 血祭：献祭生命获得攻击力翻倍buff ----
                //       条件：没有buff + HP > 1（至少留1点血）
                //       HP公式：(hp + 1) / 2 向上取整（ceil），例：HP=5→3，HP=4→2
                case 6:
                    if (!dbPlayer.isBuff() && dbPlayer.getHp() > 1) {
                        int newHp = (dbPlayer.getHp() + 1) / 2;  // 血祭后HP减半（向上取整）
                        dbPlayer.setHp(newHp);
                        dbPlayer.setBuff(true);                  // 获得buff，下次攻击伤害翻倍
                        memPlayer.setHp(newHp);
                        memPlayer.setBuff(true);
                        logMsg = dbPlayer.getName() + " 血祭，生命值降为 " + newHp + "，下次攻击伤害翻倍";
                        actionDone = true;
                    }
                    break;

                // ---- 7. 撵入：在城外把目标强制推入某个城市 ----
                //       条件：攻击者在城外 + 同位置有目标
                case 7:
                    if (!"outside".equals(dbPlayer.getLocation())) break; // 必须在城外
                    if (targetId == null) {
                        resultRef.set("请选择目标");
                        return room;
                    }
                    if (cityName == null || cityName.isEmpty()) {
                        resultRef.set("请选择城市");
                        return room;
                    }
                    Player target7 = findTargetInSameLocation(dbPlayer, room, targetId);
                    if (target7 != null) {
                        target7.setLocation(cityName);           // 强制改变目标位置
                        playerDao.update(target7);
                        logMsg = dbPlayer.getName() + " 将 " + target7.getName() + " 撵入 " + cityName;
                        actionDone = true;
                    }
                    break;
            }

            // ---------- 行动成功后的统一处理 ----------
            if (actionDone) {
                dbPlayer.setSteps(dbPlayer.getSteps() - 1);      // 消耗1步
                memPlayer.setSteps(dbPlayer.getSteps());         // 同步内存
                playerDao.update(dbPlayer);                      // 持久化玩家状态
                room.addLog(logMsg);
                logToConsole(logMsg);
            }

            // 步数归零 → 轮到下一个玩家行动
            if (dbPlayer.getSteps() <= 0) {
                room.nextPlayer();                               // 行动队列指针后移
                if (room.getActionQueue().isEmpty()) {           // 队列清空 = 所有人行动完毕
                    endRound(room);                              // → 结束当前回合
                }
            }
            resultRef.set(actionDone ? "操作成功" : "操作无效");
            return room;
        });

        return resultRef.get();
    }

    /**
     * 在同位置查找目标玩家。
     * 条件：目标存活 + ID匹配 + 与攻击者在同一位置。
     */
    private Player findTargetInSameLocation(Player self, GameRoom room, String targetId) {
        for (Player p : room.getPlayers()) {
            if (p.isAlive()                                  // 目标必须存活
                    && p.getId().equals(targetId)            // ID匹配
                    && p.getLocation().equals(self.getLocation())) { // 同一位置
                return p;
            }
        }
        return null;
    }

    // ==================================================================================
    //  回合与游戏结束
    // ==================================================================================

    /**
     * 检查游戏是否结束。
     * 当存活玩家只剩1人时，设置游戏状态为 FINISHED 并记录胜利者。
     */
    private void checkGameEnd(GameRoom room) {
        List<Player> alive = room.getAlivePlayers();
        if (alive.size() == 1) {                             // 仅剩1人存活
            room.setStatus("FINISHED");
            room.setWinner(alive.get(0).getName());          // 最后存活者获胜
            gameDao.updateStatus(room);
            room.addLog("游戏结束，胜利者：" + alive.get(0).getName());
            logToConsole("游戏结束，胜利者：" + alive.get(0).getName());
        }
    }

    /**
     * 结束当前回合。
     *
     * <p>判断逻辑：
     * <ul>
     *   <li>存活只有一个 → 游戏结束</li>
     *   <li>多个存活 → 清空所有玩家步数和猜拳状态，回合数+1，进入下一轮 GUESS 阶段</li>
     * </ul>
     */
    private void endRound(GameRoom room) {
        List<Player> alive = room.getAlivePlayers();
        if (alive.size() == 1) {
            // 游戏结束
            room.setStatus("FINISHED");
            room.setWinner(alive.get(0).getName());
            room.addLog("游戏结束，胜利者：" + alive.get(0).getName());
            logToConsole("游戏结束，胜利者：" + alive.get(0).getName());
            gameDao.updateStatus(room);
        } else {
            // 进入下一回合
            alive.forEach(p -> {
                p.setSteps(0);                               // 步数清零
                playerDao.update(p);
            });
            room.setPhase("GUESS");                          // 重置为猜拳阶段
            room.setRound(room.getRound() + 1);              // 回合数递增
            room.addLog("第 " + (room.getRound() - 1) + " 回合结束，进入第 " + room.getRound() + " 回合猜拳");
            logToConsole("第 " + (room.getRound() - 1) + " 回合结束，进入第 " + room.getRound() + " 回合猜拳");
            gameDao.updateStatus(room);
        }
    }

    // ==================================================================================
    //  玩家退出与房间清理
    // ==================================================================================

    /**
     * 玩家主动离开房间。
     *
     * <p>从数据库删除玩家记录、从内存玩家列表中移除该玩家，
     * 彻底清理该玩家的所有痕迹。如果房间内没有其他玩家，则一并清理房间。</p>
     */
    public void playerLeave(String roomId, String playerId) {
        rooms.compute(roomId, (id, room) -> {
            if (room == null) return null;

            // 先查 DB 获取玩家名称（用于日志）
            Player dbPlayer = playerDao.findById(playerId);
            String playerName = dbPlayer != null ? dbPlayer.getName() : playerId;

            // 从数据库删除该玩家记录
            playerDao.deleteById(playerId);

            // 从内存玩家列表中移除
            room.getPlayers().removeIf(p -> p.getId().equals(playerId));

            room.addLog(playerName + " 离开了房间");
            logToConsole(playerName + " 离开了房间");

            // 房间为空则清理房间
            if (room.getPlayers().isEmpty()) {
                cleanupRoomData(roomId);
                return null;
            }
            return room;
        });
    }

    /**
     * 仅清理数据库，不操作 rooms map（在 compute 回调中通过返回 null 来移除）。
     */
    private void cleanupRoomData(String roomId) {
        playerDao.deleteByRoomId(roomId);
        gameDao.deleteById(roomId);
        System.out.println("房间 " + roomId + " 已被彻底清理");
    }

    /**
     * 定时清理不活跃房间。
     *
     * <p>每 5 秒执行一次（{@code fixedRate=5000}），清理条件：
     * <ul>
     *   <li>房间状态为 FINISHED 或所有玩家 lastActivity 超过 2 分钟未更新</li>
     * </ul>
     * 选择 5 秒扫描 + 2 分钟阈值的理由：
     * 5 秒间隔保证断线检测及时（游戏进行中），2 分钟宽限期避免网络波动误清理。
     * </p>
     */
    @Scheduled(fixedRate = 5000)                             // Spring定时任务：每5秒执行
    public void cleanupInactiveRooms() {
        long inactiveThreshold = System.currentTimeMillis() - 2 * 60 * 1000; // 2分钟前
        long disconnectThreshold = 5 * 1000;                 // 断线阈值：5秒无活动
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, GameRoom> entry : rooms.entrySet()) {
            GameRoom room = entry.getValue();
            // 检查所有玩家是否都不活跃
            boolean allInactive = room.getPlayers().stream().allMatch(p -> {
                Timestamp last = p.getLastActivity();
                return last == null || last.getTime() < inactiveThreshold;
            });
            // 已结束 或 全部不活跃 → 标记清理
            if (("FINISHED".equals(room.getStatus()) || allInactive) && allInactive) {
                toRemove.add(entry.getKey());
            }

            // ---- 单个玩家断线检测（仅在游戏进行中） ----
            if ("PLAYING".equals(room.getStatus())) {
                for (Player p : room.getPlayers()) {
                    Timestamp last = p.getLastActivity();
                    if (last == null || last.getTime() == 0) continue; // 跳过从未活动或已主动退出
                    long idle = System.currentTimeMillis() - last.getTime();
                    if (idle > disconnectThreshold) {
                        // 玩家断线，但尚未记录过 → 写入日志
                        // 注意：contains + add 非原子操作，但在 ConcurrentHashMap
                        // compute 回调内串行执行，且 rooms.entrySet() 迭代时不会并发修改自身，安全
                        if (!room.getDisconnectedLogged().contains(p.getId())) {
                            room.addLog(p.getName() + " 异常断开");
                            logToConsole(p.getName() + " 异常断开");
                            room.getDisconnectedLogged().add(p.getId());
                        }
                    } else {
                        // 玩家恢复在线 → 清除断线标记，允许下次断线时重新记录
                        room.getDisconnectedLogged().remove(p.getId());
                    }
                }
            }
        }
        for (String rid : toRemove) {                        // 批量清理
            rooms.compute(rid, (id, room) -> {
                if (room != null) {
                    cleanupRoomData(rid);
                }
                return null; // 返回 null 从 map 中移除
            });
            logToConsole("定时清理房间：" + rid);
        }
    }

    // ==================================================================================
    //  控制台日志（带客户端IP追踪）
    // ==================================================================================

    /**
     * 输出带客户端IP的日志到控制台。
     * 用于服务端调试和监控玩家行为。
     */
    private void logToConsole(String msg) {
        String ip = getClientIp();
        if (ip != null) {
            System.out.println("[IP: " + ip + "] " + msg);
        } else {
            System.out.println("[无] " + msg);
        }
    }

    /**
     * 获取当前 HTTP 请求的客户端真实IP地址。
     *
     * <p>按优先级尝试多个Header（适配代理/负载均衡场景）：
     * <ol>
     *   <li>X-Forwarded-For（标准代理头）</li>
     *   <li>Proxy-Client-IP（Apache代理）</li>
     *   <li>WL-Proxy-Client-IP（WebLogic代理）</li>
     *   <li>request.getRemoteAddr()（直连IP）</li>
     * </ol>
     * 在非Web线程（如 @Scheduled 定时任务）中调用会抛出 IllegalStateException，返回 null。</p>
     *
     * @return 客户端IP字符串，无法获取时返回 null
     */
    private String getClientIp() {
        try {
            // 从Spring上下文中获取当前请求
            HttpServletRequest request = ((ServletRequestAttributes)
                    RequestContextHolder.currentRequestAttributes()).getRequest();
            String ip = request.getHeader("X-Forwarded-For");     // 第1优先级：标准代理头
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("Proxy-Client-IP");        // 第2优先级：Apache代理
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("WL-Proxy-Client-IP");     // 第3优先级：WebLogic代理
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();                     // 第4优先级：直连IP
            }
            return ip;
        } catch (IllegalStateException e) {
            // 非Web请求线程（如定时任务），无法获取IP
            return null;
        }
    }
}