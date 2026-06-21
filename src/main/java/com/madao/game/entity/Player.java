package com.madao.game.entity;

import java.sql.Timestamp;

/**
 * 玩家实体类 —— 表示游戏中的一名玩家。
 *
 * <p>每个玩家拥有独立的生命值、步数、装备（马/刀/buff）和位置信息。
 * 玩家数据会持久化到 H2 数据库的 player 表中，支持断线重连。</p>
 *
 * <p>所属模块：实体层（Entity），被 Service 层和 DAO 层共同使用。</p>
 *
 * @author madao
 */
public class Player {

    /** 玩家唯一标识（UUID），数据库主键 */
    private String id;

    /** 所属房间ID，外键关联 game_room 表 */
    private String roomId;

    /** 玩家昵称，同一房间内不可重复 */
    private String name;

    /** 生命值（HP），默认10，归零则该玩家出局 */
    private int hp = 10;

    /** 当前拥有的步数（行动点数），猜拳获胜获得，执行行动消耗 */
    private int steps = 0;

    /** 是否拥有马 —— 拥有马才能使用"踢"技能（需同在城内） */
    private boolean horse = false;

    /** 是否拥有刀 —— 拥有刀才能使用"刺"技能（需同位置） */
    private boolean knife = false;

    /** 是否有血祭buff —— buff存在时下一次攻击伤害翻倍，攻击后buff消失 */
    private boolean buff = false;

    /** 当前位置：格式为 "city-N"（城内）或 "outside"（城外） */
    private String location;

    /** 是否存活，死亡后进入观战模式 */
    private boolean alive = true;

    /** 本轮猜拳手势：石头/剪刀/布，null 表示尚未猜拳 */
    private String guess;

    /** 最后一次活动时间戳，用于判断玩家是否在线（5秒内有活动则视为在线） */
    private Timestamp lastActivity;

    // ========================== Getter / Setter ==========================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getHp() { return hp; }
    public void setHp(int hp) { this.hp = hp; }

    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }

    /** @return 是否拥有马 */
    public boolean isHorse() { return horse; }
    public void setHorse(boolean horse) { this.horse = horse; }

    /** @return 是否拥有刀 */
    public boolean isKnife() { return knife; }
    public void setKnife(boolean knife) { this.knife = knife; }

    /** @return 是否有血祭buff（下次攻击伤害翻倍） */
    public boolean isBuff() { return buff; }
    public void setBuff(boolean buff) { this.buff = buff; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }

    public String getGuess() { return guess; }
    public void setGuess(String guess) { this.guess = guess; }

    public Timestamp getLastActivity() { return lastActivity; }
    public void setLastActivity(Timestamp lastActivity) { this.lastActivity = lastActivity; }
}