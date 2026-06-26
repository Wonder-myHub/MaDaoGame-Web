/* ============================================================
 * 马刀游戏 公共脚本 (common.js)
 * 包含：
 *   1. HTML 转义函数 (escapeHtml) — 防 XSS
 *   2. 规则弹窗控制 (toggleRules/restoreRulesPopup/click监听)
 *   3. 通用 UI 更新函数 (updatePlayers/updateLogs/updateChat)
 *   4. 聊天发送逻辑
 *
 * 依赖：需要全局变量 roomId、playerId 已在页面中通过 Thymeleaf 注入
 * common.js 整体架构
 *├── escapeHtml()           ← 安全层：XSS 防注入
 *├── toggleRules()          ← 交互层：规则弹窗控制
 *├── updatePlayers()        ← 渲染层：玩家卡片
 *├── updateLogs()           ← 渲染层：操作日志（增量优化）
 *├── updateChat()           ← 渲染层：聊天面板（增量优化）
 *├── initChatForm()         ← 交互层：聊天发送（双请求）
 *├── smartPoll()            ← 网络层：智能轮询+心跳
 *└── createRoomPoller()     ← 网络层：轮询工厂（消除重复）
 * ============================================================ */

// ========== HTML 转义函数（防 XSS） ==========

/**
 * 将用户输入的字符串转义为安全的 HTML 文本
 * 防止通过 innerHTML 渲染时触发 XSS 攻击
 *
 * @param {string} str - 原始用户输入字符串
 * @returns {string} 转义后的安全字符串
 */
function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// ========== 规则弹窗控制 ==========

/**
 * 获取 sessionStorage 中规则弹窗状态的键名。
 * 基于 roomId 区分不同房间的弹窗状态，避免多房间冲突。
 * @returns {string} sessionStorage 键名
 */
function rulesKey() {
    return 'rulesPopupVisible_' + (typeof roomId !== 'undefined' ? roomId : 'global');
}

/**
 * 切换规则弹窗的显示/隐藏状态
 * 使用 sessionStorage 持久化状态，页面刷新后不丢失
 * 已添加 null 检查，防止 popup 元素不存在时抛出错误
 */
function toggleRules() {
    var popup = document.getElementById('rulesPopup');
    if (!popup) return;
    popup.classList.toggle('show');
    sessionStorage.setItem(rulesKey(), String(popup.classList.contains('show')));
}

/**
 * 从 sessionStorage 恢复规则弹窗的显示状态
 * 在每次 AJAX 刷新后调用，确保弹窗状态不被重置
 */
function restoreRulesPopup() {
    var popup = document.getElementById('rulesPopup');
    if (popup && sessionStorage.getItem(rulesKey()) === 'true') {
        popup.classList.add('show');
    }
}

/**
 * 全局 click 事件监听器（事件委托策略）
 *
 * 策略说明：
 *   - 利用 window 级别的 click 事件统一处理规则弹窗的关闭，无需为每个页面元素单独绑定
 *   - 当用户点击 .rules-container 以外的任意区域时，自动关闭规则弹窗
 *   - 通过 container.contains(e.target) 判断点击是否在弹窗内部，实现"点击外部关闭"的常见 UX 模式
 *
 * 关闭规则弹窗的逻辑：
 *   1. 获取 .rules-container 和 #rulesPopup 元素（含 null 检查，防御动态页面中元素不存在）
 *   2. 若点击目标不在 rules-container 内，则移除 .show 类名（CSS display:none 隐藏弹窗）
 *   3. 同步更新 sessionStorage，确保弹窗状态在页面刷新/AJAX 刷新后不被意外恢复
 *
 * 注意：此处使用 addEventListener 而非 onclick 属性，避免与其他脚本中的 onclick 冲突
 */
window.addEventListener('click', function(e) {
    var container = document.querySelector('.rules-container');
    if (!container) return;
    var popup = document.getElementById('rulesPopup');
    if (!popup) return;
    if (!container.contains(e.target)) {
        popup.classList.remove('show');
        sessionStorage.setItem(rulesKey(), 'false');
    }
});

// ========== 通用 UI 更新函数 ==========

/**
 * 更新玩家状态卡片列表
 *
 * @param {Array<Object>} players - 玩家信息数组
 *   每个元素字段: id, name, hp, steps, horse, knife, buff, location
 */
function updatePlayers(players) {
    if (!players) return;
    var container = document.getElementById('playersContainer');
    if (!container) return;
    var html = '';
    for (var i = 0; i < players.length; i++) {
        var p = players[i];
        var isMe = p.id === playerId;
        var deadClass = p.hp <= 0 ? ' dead' : '';
        var currentClass = isMe ? ' current' : '';

        // 开始拼接玩家卡片 HTML —— 每个字段对应后端 Player 实体属性
        html += '<div class="player-card' + currentClass + deadClass + '">'; // 容器：拼接 current（当前玩家高亮）和 dead（死亡灰显）CSS 类名
        // 玩家名称行：含"（我）"标识和断线标记
        html += '<p><strong>' + escapeHtml(p.name) + '</strong>'
             + (isMe ? '（我）' : '')
             + (p.online === false ? '<span class="disconnected">(断线)</span>' : '')
             + '</p>';
        html += '<p>🐴 ' + (p.horse ? '有' : '无') + '</p>'; // 🐴 马：boolean 值，true=有/可踢人
        html += '<p>🔪 ' + (p.knife ? '有' : '无') + '</p>'; // 🔪 刀：boolean 值，true=有/可刺人
        html += '<p>❤️ ' + (p.hp > 0 ? p.hp : 0) + '</p>';  // ❤️ 生命值：HP<=0 时显示0（玩家出局）
        html += '<p>📍 ' + escapeHtml(p.location) + '</p>';  // 📍 当前位置：城内名或"城外"
        if (p.steps > 0) html += '<p>👣 ' + p.steps + '</p>'; // 👣 剩余步数：仅在 >0 时显示
        if (p.buff && p.hp > 0) html += '<p class="blood-buff">🔥 血祭</p>'; // 🔥 血祭 buff：有 buff 且存活时显示
        html += '</div>';
    }
    container.innerHTML = html;
}

/**
 * 更新操作记录列表
 * 优化：先比较数量和内容，仅在变化时重新渲染，减少闪烁
 *
 * @param {Array<string>} logs - 操作记录字符串数组
 */
function updateLogs(logs) {
    if (!logs) return;
    var logList = document.getElementById('logList');
    if (!logList) return;
    // 优化策略：避免不必要的 DOM 重绘，减少轮询时的页面闪烁
    // 步骤1：先比较数组长度——长度不同必然需要更新，O(1) 复杂度快速短路
    var needUpdate = logList.children.length !== logs.length;
    if (!needUpdate) {
        // 步骤2：长度相同时逐条比较 textContent——提取纯文本，忽略 HTML 标签差异
        // 注意：此处 logs[i] 是原始文本（未包装 <li>），直接与现有 DOM 的 textContent 比较
        for (var i = 0; i < logs.length; i++) {
            if (logList.children[i] && logList.children[i].textContent !== logs[i]) {
                needUpdate = true; break; // 发现差异立即跳出，不再继续比较
            }
        }
    }
    if (needUpdate) {
        var html = '';
        for (var i = 0; i < logs.length; i++) {
            html += '<li>' + escapeHtml(logs[i]) + '</li>';
        }
        logList.innerHTML = html;
        var logScroll = document.getElementById('logScroll');
        if (logScroll) logScroll.scrollTop = logScroll.scrollHeight;
    }
}

/**
 * 更新聊天消息列表
 * 优化：先比较数量和内容，仅在变化时重新渲染，减少闪烁
 *
 * @param {Array<string>} messages - 聊天消息字符串数组
 */
function updateChat(messages) {
    if (!messages) return;
    var chatList = document.getElementById('chatList');
    if (!chatList) return;
    // 优化策略：与 updateLogs 相同——先比数量再比内容，避免不必要的 DOM 重绘
    var needUpdate = chatList.children.length !== messages.length;
    if (!needUpdate) {
        // 长度相同时逐条比较 textContent——提取纯文本进行比较，忽略 HTML 标签差异
        for (var i = 0; i < messages.length; i++) {
            if (chatList.children[i] && chatList.children[i].textContent !== messages[i]) {
                needUpdate = true; break;
            }
        }
    }
    if (needUpdate) {
        var html = '';
        for (var i = 0; i < messages.length; i++) {
            html += '<li>' + escapeHtml(messages[i]) + '</li>';
        }
        chatList.innerHTML = html;
        var chatScroll = document.getElementById('chatScroll');
        if (chatScroll) chatScroll.scrollTop = chatScroll.scrollHeight;
    }
}

// ========== 聊天发送 ==========

/**
 * 聊天表单提交事件处理
 *
 * 双请求策略说明：
 *   请求1（POST /api/chat）：将用户输入的消息发送到后端，后端将消息存入房间聊天记录并广播
 *   请求2（GET  /api/room）：在 POST 成功后立即拉取完整房间状态快照
 *
 * 为什么需要两次请求：
 *   - POST chat 仅发送消息，不返回更新后的聊天列表
 *   - GET room 返回房间全量数据（含 chatMessages[]），确保 UI 显示的是后端最新权威状态
 *   - 这种"写后即读"模式避免了前端自行拼接聊天消息可能导致的顺序/遗漏问题
 *
 * 注意：两个 await 是顺序执行的，POST 失败时 GET 不会执行（被 catch 捕获）
 */
function initChatForm() {
    var chatForm = document.getElementById('chatForm');
    if (!chatForm) return;
    chatForm.addEventListener('submit', async function(e) {
        e.preventDefault();
        var input = document.getElementById('chatInput');
        if (!input) return;
        var message = input.value.trim();
        if (!message) return;
        var formData = new FormData();
        formData.append('message', message);
        try {
            // 请求1：POST 发送聊天消息到后端
            await fetch('/api/chat/' + roomId + '/' + playerId, { method: 'POST', body: formData });
            input.value = ''; // 清空输入框，提供即时反馈
            // 请求2：GET 拉取完整房间状态，获取包含新消息在内的最新聊天记录
            var res = await fetch('/api/room/' + roomId + '?playerId=' + playerId);
            var data = await res.json();
            updateChat(data.chatMessages); // 用后端权威数据刷新聊天面板
        } catch (err) {
            console.error(err);
        }
    });
}

// ========== 智能轮询（页面隐藏时保持心跳） ==========

/**
 * smartPoll(fn, intervalMs) — 智能定时轮询
 *
 * 相比 setInterval 的改进：
 *   1. 当页面不可见（切到后台标签页）时跳过 UI 刷新（fn），但持续发送心跳保持在线
 *   2. 页面从隐藏恢复可见时立即执行 fn()，无需等待轮询间隔到期
 *   3. 首次立即执行 fn()，无需等待第一个间隔
 *   4. 返回 { stop } 对象以便手动停止
 *
 * @param {function} fn          — 轮询回调函数
 * @param {number}   intervalMs  — 轮询间隔（毫秒）
 * @returns {{ stop: function }} — 包含 stop 方法，调用可停止轮询
 */
function smartPoll(fn, intervalMs) {
    var timer;
    function schedule() {
        timer = setTimeout(function() {
            if (document.hidden) {
                // 页面隐藏时：跳过 UI 刷新，但发送心跳保持在线
                if (typeof roomId !== 'undefined' && typeof playerId !== 'undefined') {
                    fetch('/api/room/' + roomId + '?playerId=' + playerId);
                }
                schedule();
                return;
            }
            fn();
            schedule();
        }, intervalMs);
    }

    // 页面从隐藏恢复可见时立即刷新，无需等待 setTimeout 到期
    // 页面变为隐藏时立即发送一次心跳，在浏览器开始限流定时器前刷新 lastActivity
    function onVisibilityChange() {
        if (document.hidden) {
            // 页面即将隐藏 → 立即发送心跳，确保 lastActivity 在进入后台前是最新的
            if (typeof roomId !== 'undefined' && typeof playerId !== 'undefined') {
                fetch('/api/room/' + roomId + '?playerId=' + playerId);
            }
        } else {
            // 页面恢复可见 → 立即刷新 UI
            fn();
        }
    }
    document.addEventListener('visibilitychange', onVisibilityChange);

    schedule();
    fn(); // 首次立即执行
    return {
        stop: function() {
            clearTimeout(timer);
            document.removeEventListener('visibilitychange', onVisibilityChange);
        }
    };
}

// ========== 通用房间轮询工厂函数 ==========

/**
 * createRoomPoller(config) — 创建适配不同页面的房间轮询函数
 *
 * 提取 waiting/spectate/result 三个页面中重复的 fetchState 模式：
 *   1. GET /api/room/{roomId}?playerId={playerId}
 *   2. 检查 room 是否存在 → 不存在跳首页
 *   3. 更新 UI（players/logs/chat）
 *   4. 根据状态/阶段判断是否跳转
 *   5. 设计意图：waiting.html、spectate.html、result.html 三个页面都需要定时拉取房间状态，且模式相同
 *   （请求 → 检查 → 更新 UI → 判断跳转）。把共同逻辑抽出，差异部分通过 config 配置注入
 * @param {Object} config — 配置对象
 *   config.shouldRedirect(data)   — 返回 true 则跳转到 /game/{roomId}/{playerId}
 *   config.onUpdate(data)         — 自定义 UI 更新回调（在通用更新之后调用）
 *   config.intervalMs             — 轮询间隔（毫秒），默认 3000
 * @returns {Function} — 返回 fetchState 函数，调用方自行启动轮询
 */
function createRoomPoller(config) {
    config = config || {};
    return async function fetchState() {
        try {
            var url = '/api/room/' + roomId + '?playerId=' + playerId;
            var res = await fetch(url);
            if (!res.ok) return;
            var data = await res.json();
            if (!data.status) { window.location.href = '/'; return; }

            // 通用 UI 更新
            updatePlayers(data.players);
            updateLogs(data.actionLogs);
            updateChat(data.chatMessages);

            // 页面特定的跳转逻辑
            if (config.shouldRedirect && config.shouldRedirect(data)) {
                window.location.href = '/game/' + roomId + '/' + playerId;
                return;
            }

            // 页面特定的 UI 更新
            if (config.onUpdate) config.onUpdate(data);

            restoreRulesPopup();
        } catch (e) { console.error('状态刷新失败', e); }
    };
}
