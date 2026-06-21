package com.madao.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 马刀游戏（MaDaoGame）—— Spring Boot 应用入口。
 *
 * <p>这是一个基于 Web 的多人回合制对战游戏，技术栈为：
 * Spring Boot 3.4.x + Thymeleaf 模板引擎 + H2 嵌入式数据库 + 原生 JDBC。</p>
 *
 * <h3>注解说明</h3>
 * <ul>
 *   <li>{@code @SpringBootApplication} — 启用自动配置、组件扫描、配置类</li>
 *   <li>{@code @EnableScheduling} — 启用定时任务支持，
 *       用于 {@code GameService.cleanupInactiveRooms()} 每60秒清理僵尸房间</li>
 * </ul>
 *
 * <h3>启动后访问</h3>
 * <p>默认地址：<a href="http://localhost:8080/">http://localhost:8080/</a>（端口在 application.properties 中配置）</p>
 *
 * @author madao
 */
@SpringBootApplication
@EnableScheduling
public class MadaoGameApplication {

    /**
     * Spring Boot 应用主入口。
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MadaoGameApplication.class, args);
    }
}