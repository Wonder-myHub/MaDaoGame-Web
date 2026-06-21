package com.madao.game.controller;

import com.madao.game.dao.PlayerDao;
import com.madao.game.entity.GameRoom;
import com.madao.game.entity.Player;
import com.madao.game.service.GameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 游戏前端控制器 —— 处理页面路由和 AJAX API 请求。
 *
 * <h2>路由分类</h2>
 * <h3>页面路由（返回 Thymeleaf 模板）</h3>
 * <table border="1">
 *   <tr><th>路由</th><th>方法</th><th>功能</th></tr>
 *   <tr><td>/</td><td>GET</td><td>首页（创建/加入房间表单）</td></tr>
 *   <tr><td>/create</td><td>POST</td><td>创建房间 → 重定向到大厅</td></tr>
 *   <tr><td>/join</td><td>POST</td><td>加入房间 → 根据状态重定向大厅/游戏</td></tr>
 *   <tr><td>/lobby/{roomId}/{playerId}</td><td>GET</td><td>等待大厅（显示玩家列表、聊天）</td></tr>
 *   <tr><td>/game/{roomId}/{playerId}</td><td>GET</td><td>游戏主页面（猜拳/行动/观战/结果）</td></tr>
 *   <tr><td>/guess/{roomId}/{playerId}</td><td>POST</td><td>提交猜拳 → 重定向回游戏页</td></tr>
 *   <tr><td>/action/{roomId}/{playerId}</td><td>POST</td><td>执行行动 → 重定向回游戏页</td></tr>
 *   <tr><td>/leave/{roomId}/{playerId}</td><td>GET</td><td>离开房间 → 回到首页</td></tr>
 *   <tr><td>/chat/{roomId}/{playerId}</td><td>POST</td><td>发送聊天消息 → 重定向</td></tr>
 * </table>
 *
 * <h3>AJAX API（返回 JSON，前缀 /api/）</h3>
 * <table border="1">
 *   <tr><th>路由</th><th>方法</th><th>功能</th></tr>
 *   <tr><td>/api/room/{roomId}</td><td>GET</td><td>获取房间完整状态（玩家数据、日志、可用操作）</td></tr>
 *   <tr><td>/api/guess/{roomId}/{playerId}</td><td>POST</td><td>AJAX猜拳 → 返回房间状态</td></tr>
 *   <tr><td>/api/action/{roomId}/{playerId}</td><td>POST</td><td>AJAX行动 → 返回房间状态+操作结果</td></tr>
 *   <tr><td>/api/chat/{roomId}/{playerId}</td><td>POST</td><td>AJAX聊天 → 返回聊天消息列表</td></tr>
 *   <tr><td>/lobby/{roomId}/{playerId}/start</td><td>POST</td><td>房主强制开始（@ResponseBody JSON）</td></tr>
 * </table>
 *
 * <h2>状态更新</h2>
 * <p>每次页面/AJAX访问都会更新玩家的 lastActivity（心跳），用于在线判定和超时清理。</p>
 *
 * <h2>页面跳转逻辑</h2>
 * <p>/{@code game} 路由根据 room.status 和 player 状态分发到不同 Thymeleaf 模板：
 * <ul>
 *   <li>游戏结束 → result 模板</li>
 *   <li>玩家死亡 → spectate 模板（观战）</li>
 *   <li>GUESS 阶段 + 未出拳 → guess 模板</li>
 *   <li>GUESS 阶段 + 已出拳 → waiting 模板</li>
 *   <li>ACTION 阶段 + 轮到当前玩家 → action 模板</li>
 *   <li>ACTION 阶段 + 不轮到 → waiting 模板</li>
 * </ul>
 * </p>
 *
 * @author madao
 */
@Controller
public class GameController {

    @Autowired
    private GameService gameService;

    @Autowired
    private PlayerDao playerDao;

    // ==================================================================================
    //  页面路由
    // ==================================================================================

    /**
     * 首页 —— 创建房间 / 加入房间入口。
     * @return index 模板
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * 创建新房间。
     * 调用 GameService 创建房间 → 自动以房主身份加入 → 重定向到大厅。
     *
     * @param count 最大玩家数
     * @param name  房主昵称
     * @return 重定向到 /lobby/{roomId}/{playerId}
     */
    @PostMapping("/create")
    public String create(@RequestParam int count, @RequestParam String name, Model model) {
        if (count < 2) {
            model.addAttribute("error", "玩家数量至少为2人");
            return "index";                                  // 校验失败，回首页
        }
        try {
            String roomId = gameService.createRoom(count);   // 创建房间
            String playerId = gameService.joinRoom(roomId, name.trim()); // 房主自动加入
            return "redirect:/lobby/" + roomId + "/" + playerId; // 跳转大厅
        } catch (RuntimeException e) {
            model.addAttribute("error", "创建失败：" + e.getMessage());
            return "index";
        }
    }

    /**
     * 加入已有房间。
     * 根据房间当前状态决定重定向目标：游戏中 → /game，等待中 → /lobby。
     */
    @PostMapping("/join")
    public String join(@RequestParam String roomId, @RequestParam String name, Model model) {
        try {
            String playerId = gameService.joinRoom(roomId.trim(), name.trim());
            GameRoom room = gameService.getRoom(roomId.trim());
            if (room != null && ("PLAYING".equals(room.getStatus()) || "FINISHED".equals(room.getStatus()))) {
                return "redirect:/game/" + roomId.trim() + "/" + playerId;  // 已在游戏中
            } else {
                return "redirect:/lobby/" + roomId.trim() + "/" + playerId; // 大厅等待
            }
        } catch (RuntimeException e) {
            model.addAttribute("error", "加入失败：" + e.getMessage());
            return "index";
        }
    }

    /**
     * 等待大厅页面。
     * 显示房间内玩家列表、聊天消息，等待人满或房主开始游戏。
     * 访问时更新玩家 lastActivity（心跳）。
     */
    @GetMapping("/lobby/{roomId}/{playerId}")
    public String lobby(@PathVariable String roomId, @PathVariable String playerId, Model model) {
        GameRoom room = gameService.getRoom(roomId);
        Player player = playerDao.findById(playerId);
        if (room == null || player == null) {
            model.addAttribute("error", "房间或玩家不存在");
            return "index";
        }

        // 更新最后活动时间（心跳机制，保持在线状态）
        player.setLastActivity(new Timestamp(System.currentTimeMillis()));
        playerDao.update(player);

        // 如果游戏已经开始，跳转到游戏页面
        if ("PLAYING".equals(room.getStatus())) {
            return "redirect:/game/" + roomId + "/" + playerId;
        }

        model.addAttribute("room", room);
        model.addAttribute("roomId", roomId);
        model.addAttribute("playerId", playerId);
        model.addAttribute("player", player);
        model.addAttribute("chatMessages", room.getChatMessages());
        return "lobby";
    }

    /**
     * 游戏主页面 —— 根据玩家状态和游戏阶段分发到不同子模板。
     *
     * <h3>页面分发逻辑</h3>
     * <ol>
     *   <li>{@code FINISHED} → result 模板（显示胜利者）</li>
     *   <li>玩家死亡 → spectate 模板（观战模式）</li>
     *   <li>{@code GUESS} + 未出拳 → guess 模板（猜拳界面）</li>
     *   <li>{@code GUESS} + 已出拳 → waiting 模板（等待他人）</li>
     *   <li>{@code ACTION} + 轮到当前玩家 → action 模板（行动界面）</li>
     *   <li>{@code ACTION} + 不轮到 → waiting 模板（观看他人行动）</li>
     * </ol>
     */
    @GetMapping("/game/{roomId}/{playerId}")
    public String game(@PathVariable String roomId, @PathVariable String playerId, Model model) {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) return "redirect:/";

        // 查找当前玩家（可能已退出或死亡）
        Player player = findPlayerInRoom(room, playerId);

        // 心跳更新
        if (player != null) {
            player.setLastActivity(new Timestamp(System.currentTimeMillis()));
            playerDao.update(player);
        }

        model.addAttribute("roomId", roomId);
        model.addAttribute("playerId", playerId);
        model.addAttribute("room", room);
        model.addAttribute("chatMessages", room.getChatMessages());

        // --- 状态1：游戏结束 → 结果页 ---
        if ("FINISHED".equals(room.getStatus())) {
            model.addAttribute("winner", room.getWinner());
            model.addAttribute("player", player);
            return "result";
        }

        // --- 状态2：玩家死亡 → 观战页 ---
        if (player == null || !player.isAlive()) {
            model.addAttribute("player", player);
            return "spectate";
        }

        model.addAttribute("player", player);

        // --- 状态3：猜拳阶段 ---
        if ("GUESS".equals(room.getPhase())) {
            if (player.getGuess() == null) {
                return "guess";                              // 未出拳 → 猜拳界面
            } else {
                model.addAttribute("message", "等待其他玩家猜拳...");
                model.addAttribute("waitingForGuess", room.getPlayersNotGuessed()); // 未出拳玩家列表
                return "waiting";                            // 已出拳 → 等待他人
            }
        }
        // --- 状态4：行动阶段 ---
        else if ("ACTION".equals(room.getPhase())) {
            if (room.isCurrentPlayer(playerId) && player.getSteps() > 0) {
                // 轮到当前玩家行动 → 构建可用操作和可选目标
                List<Map<String, Object>> availableActions = buildAvailableActions(player, room);
                model.addAttribute("availableActions", availableActions);

                // 同位置的可攻击目标（排除自己）
                List<Player> targets = room.getAlivePlayers().stream()
                        .filter(p -> !p.getId().equals(playerId)
                                && p.getLocation().equals(player.getLocation()))
                        .collect(Collectors.toList());
                model.addAttribute("targets", targets);

                // 所有可移动的城市列表（city-1 ~ city-N）
                List<String> cities = new ArrayList<>();
                for (int i = 1; i <= room.getPlayerCount(); i++) {
                    cities.add("city-" + i);
                }
                model.addAttribute("cities", cities);

                return "action";                             // 行动界面
            } else {
                // 不轮到当前玩家 → 等待界面（显示当前行动者信息）
                model.addAttribute("message", "等待其他玩家行动...");
                model.addAttribute("currentActionPlayer", room.getCurrentActionPlayerName());
                model.addAttribute("currentActionSteps", room.getCurrentActionPlayerSteps());
                return "waiting";
            }
        }
        return "redirect:/";
    }

    /**
     * 提交猜拳手势（传统表单方式，POST后重定向）。
     */
    @PostMapping("/guess/{roomId}/{playerId}")
    public String guess(@PathVariable String roomId, @PathVariable String playerId,
                        @RequestParam String gesture) {
        gameService.submitGuess(playerId, gesture);
        return "redirect:/game/" + roomId + "/" + playerId;
    }

    /**
     * 执行行动（传统表单方式，POST后重定向）。
     *
     * @param actionType 行动类型（0-7）
     * @param targetId   目标玩家ID（攻击操作需要，可为null）
     * @param city       目标城市（移动/撵入操作需要，可为null）
     */
    @PostMapping("/action/{roomId}/{playerId}")
    public String action(@PathVariable String roomId, @PathVariable String playerId,
                         @RequestParam int actionType,
                         @RequestParam(required = false) String targetId,
                         @RequestParam(required = false) String city) {
        gameService.executeAction(playerId, actionType, targetId, city);
        return "redirect:/game/" + roomId + "/" + playerId;
    }

    /**
     * 玩家主动离开房间。
     * 将 lastActivity 设为 epoch(0) 标记退出，阻止重连。
     */
    @GetMapping("/leave/{roomId}/{playerId}")
    public String leave(@PathVariable String roomId, @PathVariable String playerId) {
        gameService.playerLeave(roomId, playerId);
        return "redirect:/";
    }

    /**
     * 发送聊天消息（传统表单方式）。
     * 消息格式为 "昵称：内容"，仅非空消息被记录。
     */
    @PostMapping("/chat/{roomId}/{playerId}")
    public String chat(@PathVariable String roomId, @PathVariable String playerId,
                       @RequestParam String message) {
        Player player = playerDao.findById(playerId);
        if (player != null && message != null && !message.trim().isEmpty()) {
            GameRoom room = gameService.getRoom(roomId);
            if (room != null) {
                room.addChatMessage(player.getName() + "：" + message.trim());
            }
        }
        return "redirect:/game/" + roomId + "/" + playerId;
    }

    // ==================================================================================
    //  AJAX API（返回 JSON，带 @ResponseBody 注解）
    // ==================================================================================

    /**
     * 【核心API】获取房间完整状态 —— 前端轮询此接口驱动游戏界面。
     *
     * <h3>返回数据结构</h3>
     * <pre>
     * {
     *   roomId, status, phase, round, winner, playerCount,
     *   players: [{id, name, hp, steps, horse, knife, buff, location, alive, guess}],
     *   currentPlayerId, currentPlayerName, currentPlayerAlive,
     *   currentPlayerSteps, currentPlayerLocation,
     *   currentPlayerHorse, currentPlayerKnife, currentPlayerBuff,
     *   isCurrentActionPlayer,
     *   actionLogs, chatMessages,
     *   playersNotGuessed (GUESS阶段),
     *   currentActionPlayer, currentActionSteps (ACTION阶段),
     *   availableActions (轮到当前玩家行动时),
     *   targets (轮到当前玩家+有同城目标时),
     *   cities (轮到当前玩家行动时)
     * }
     * </pre>
     *
     * @param roomId   房间UUID
     * @param playerId 请求者玩家UUID（用于心跳+获取个人视角数据）
     * @return 房间完整状态 Map，房间不存在返回空Map
     */
    @GetMapping("/api/room/{roomId}")
    @ResponseBody
    public Map<String, Object> apiGetRoomState(@PathVariable String roomId,
                                               @RequestParam String playerId) {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) return Collections.emptyMap();

        // 心跳更新
        Player player = findPlayerInRoom(room, playerId);
        if (player != null) {
            player.setLastActivity(new Timestamp(System.currentTimeMillis()));
            playerDao.update(player);
        }

        // ---------- 房间基本信息 ----------
        Map<String, Object> result = new HashMap<>();
        result.put("roomId", room.getId());
        result.put("status", room.getStatus());
        result.put("phase", room.getPhase());
        result.put("round", room.getRound());
        result.put("winner", room.getWinner());
        result.put("playerCount", room.getPlayerCount());

        // ---------- 所有玩家列表（含隐藏信息如猜拳手势） ----------
        List<Map<String, Object>> playersJson = room.getPlayers().stream().map(p -> {
            Map<String, Object> pm = new HashMap<>();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            pm.put("hp", p.getHp());
            pm.put("steps", p.getSteps());
            pm.put("horse", p.isHorse());                  // 是否有马（决定能否"踢"）
            pm.put("knife", p.isKnife());                  // 是否有刀（决定能否"刺"）
            pm.put("buff", p.isBuff());                    // 是否有血祭buff（伤害翻倍）
            pm.put("location", p.getLocation());
            pm.put("alive", p.isAlive());
            pm.put("guess", p.getGuess());                 // 猜拳手势（他人不可见，为null）
            return pm;
        }).collect(Collectors.toList());
        result.put("players", playersJson);

        // ---------- 当前玩家视角数据 ----------
        if (player != null) {
            result.put("currentPlayerId", player.getId());
            result.put("currentPlayerName", player.getName());
            result.put("currentPlayerAlive", player.isAlive());
            result.put("currentPlayerGuess", player.getGuess());
            result.put("currentPlayerSteps", player.getSteps());
            result.put("currentPlayerLocation", player.getLocation());
            result.put("currentPlayerHorse", player.isHorse());
            result.put("currentPlayerKnife", player.isKnife());
            result.put("currentPlayerBuff", player.isBuff());
            result.put("isCurrentActionPlayer", room.isCurrentPlayer(playerId)); // 是否轮到行动
        } else {
            result.put("currentPlayerAlive", false);       // 玩家不在房间 → 视为死亡
        }

        // ---------- 日志和聊天 ----------
        result.put("actionLogs", room.getActionLogs());
        result.put("chatMessages", room.getChatMessages());

        // ---------- 阶段特定数据 ----------
        if ("GUESS".equals(room.getPhase())) {
            result.put("playersNotGuessed", room.getPlayersNotGuessed()); // 未出拳玩家列表
        } else if ("ACTION".equals(room.getPhase())) {
            result.put("currentActionPlayer", room.getCurrentActionPlayerName()); // 当前行动者
            result.put("currentActionSteps", room.getCurrentActionPlayerSteps()); // 剩余步数
        }

        // ---------- 轮到当前玩家行动时：提供可用操作和可选目标 ----------
        if (player != null && player.isAlive() && "ACTION".equals(room.getPhase())
                && room.isCurrentPlayer(playerId) && player.getSteps() > 0) {

            // 可用行动列表（根据当前状态动态生成）
            List<Map<String, Object>> actions = buildAvailableActions(player, room);
            result.put("availableActions", actions);

            // 同位置的可攻击目标
            List<Player> targets = room.getAlivePlayers().stream()
                    .filter(p -> !p.getId().equals(playerId)
                            && p.getLocation().equals(player.getLocation()))
                    .collect(Collectors.toList());
            result.put("targets", targets.stream().map(t -> {
                Map<String, Object> tm = new HashMap<>();
                tm.put("id", t.getId());
                tm.put("name", t.getName());
                tm.put("hp", t.getHp());
                return tm;
            }).collect(Collectors.toList()));

            // 所有城市列表
            List<String> cities = new ArrayList<>();
            for (int i = 1; i <= room.getPlayerCount(); i++) cities.add("city-" + i);
            result.put("cities", cities);
        }

        return result;
    }

    /**
     * AJAX 猜拳 —— 提交手势后直接返回最新房间状态。
     */
    @PostMapping("/api/guess/{roomId}/{playerId}")
    @ResponseBody
    public Map<String, Object> apiGuess(@PathVariable String roomId,
                                        @PathVariable String playerId,
                                        @RequestParam String gesture) {
        gameService.submitGuess(playerId, gesture);
        return apiGetRoomState(roomId, playerId);            // 复用状态查询
    }

    /**
     * AJAX 行动 —— 执行行动后返回最新房间状态和操作结果消息。
     */
    @PostMapping("/api/action/{roomId}/{playerId}")
    @ResponseBody
    public Map<String, Object> apiAction(@PathVariable String roomId,
                                         @PathVariable String playerId,
                                         @RequestParam int actionType,
                                         @RequestParam(required = false) String targetId,
                                         @RequestParam(required = false) String city) {
        String msg = gameService.executeAction(playerId, actionType, targetId, city);
        Map<String, Object> result = apiGetRoomState(roomId, playerId);
        result.put("actionMessage", msg);                    // 操作结果（成功/失败原因）
        return result;
    }

    /**
     * AJAX 聊天 —— 返回更新后的聊天消息列表。
     */
    @PostMapping("/api/chat/{roomId}/{playerId}")
    @ResponseBody
    public List<String> apiChat(@PathVariable String roomId,
                                @PathVariable String playerId,
                                @RequestParam String message) {
        Player player = playerDao.findById(playerId);
        if (player != null && message != null && !message.trim().isEmpty()) {
            GameRoom room = gameService.getRoom(roomId);
            if (room != null) {
                room.addChatMessage(player.getName() + "：" + message.trim());
            }
        }
        GameRoom room = gameService.getRoom(roomId);
        return room != null ? room.getChatMessages() : Collections.emptyList(); // 返回完整聊天历史
    }

    /**
     * 房主强制开始游戏（AJAX JSON 响应）。
     * 仅第一个加入房间的玩家（房主）可调用，需至少2人。
     *
     * @return {"success": true} 或 {"success": false, "error": "错误原因"}
     */
    @PostMapping("/lobby/{roomId}/{playerId}/start")
    @ResponseBody
    public Map<String, Object> forceStartGame(@PathVariable String roomId, @PathVariable String playerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            gameService.forceStartGame(roomId, playerId);
            result.put("success", true);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================================================================================
    //  辅助方法
    // ==================================================================================

    /**
     * 在房间的玩家列表中查找指定ID的玩家。
     * @param room     游戏房间
     * @param playerId 玩家UUID
     * @return 找到的 Player 对象，未找到返回 null
     */
    private Player findPlayerInRoom(GameRoom room, String playerId) {
        for (Player p : room.getPlayers()) {
            if (p.getId().equals(playerId)) return p;
        }
        return null;
    }

    /**
     * 根据玩家当前状态和房间环境，动态构建可用行动列表。
     *
     * <h3>行动可用性规则</h3>
     * <table border="1">
     *   <tr><th>编号</th><th>操作</th><th>可用条件</th></tr>
     *   <tr><td>0</td><td>放弃</td><td>始终可用</td></tr>
     *   <tr><td>1</td><td>移动</td><td>始终可用</td></tr>
     *   <tr><td>2</td><td>买马</td><td>未拥有马</td></tr>
     *   <tr><td>3</td><td>买刀</td><td>未拥有刀</td></tr>
     *   <tr><td>4</td><td>踢</td><td>在城内 + 有马 + 同城有他人</td></tr>
     *   <tr><td>5</td><td>刺</td><td>有刀 + 同位置有他人</td></tr>
     *   <tr><td>6</td><td>血祭</td><td>无buff + HP>1</td></tr>
     *   <tr><td>7</td><td>撵入</td><td>在城外 + 同位置有他人</td></tr>
     * </table>
     *
     * @param player 当前行动者
     * @param room   游戏房间
     * @return 可用行动列表，每项包含 {index, actionType, name}
     */
    private List<Map<String, Object>> buildAvailableActions(Player player, GameRoom room) {
        List<Map<String, Object>> actions = new ArrayList<>();
        int index = 0;

        // 0. 放弃 —— 始终可用
        actions.add(createAction(index++, 0, "放弃"));
        // 1. 移动 —— 始终可用
        actions.add(createAction(index++, 1, "移动"));
        // 2. 买马 —— 未拥有时可用（解锁"踢"技能）
        if (!player.isHorse()) actions.add(createAction(index++, 2, "买马"));
        // 3. 买刀 —— 未拥有时可用（解锁"刺"技能）
        if (!player.isKnife()) actions.add(createAction(index++, 3, "买刀"));
        // 4. 踢 —— 需在城内 + 有马 + 同城有可攻击目标，伤害3（buff翻倍=6），目标被踢到城外
        if (player.getLocation().startsWith("city") && player.isHorse()
                && hasOtherPlayerInSameLocation(player, room))
            actions.add(createAction(index++, 4, "踢"));
        // 5. 刺 —— 需有刀 + 同位置有可攻击目标，伤害1（buff翻倍=2）
        if (player.isKnife() && hasOtherPlayerInSameLocation(player, room))
            actions.add(createAction(index++, 5, "刺"));
        // 6. 血祭 —— 需无buff + HP>1（至少留1点血），HP减半换下次攻击伤害翻倍
        if (!player.isBuff() && player.getHp() > 1)
            actions.add(createAction(index++, 6, "血祭"));
        // 7. 撵入 —— 需在城外 + 有同位置目标，强制将目标推入指定城市
        if ("outside".equals(player.getLocation()) && hasOtherPlayerInSameLocation(player, room))
            actions.add(createAction(index++, 7, "撵进去"));

        return actions;
    }

    /**
     * 创建行动选项的 Map 对象。
     * @param index      序号（前端可能用于排序）
     * @param actionType 行动类型编码（0-7，对应 GameService 中的 switch）
     * @param name       显示名称（中文）
     */
    private Map<String, Object> createAction(int index, int actionType, String name) {
        Map<String, Object> map = new HashMap<>();
        map.put("index", index);
        map.put("actionType", actionType);
        map.put("name", name);
        return map;
    }

    /**
     * 检查是否有其他存活玩家与当前玩家在同一位置。
     * 用于判断攻击类操作（踢/刺/撵入）的目标是否存在。
     */
    private boolean hasOtherPlayerInSameLocation(Player self, GameRoom room) {
        return room.getAlivePlayers().stream()
                .anyMatch(p -> !p.getId().equals(self.getId())
                        && p.getLocation().equals(self.getLocation()));
    }
}