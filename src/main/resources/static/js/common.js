/* ============================================================
 * 马刀游戏 公共脚本 (common.js)
 * 包含：
 *   1. HTML 转义函数 (escapeHtml) — 防 XSS
 *   2. 规则弹窗控制 (toggleRules/restoreRulesPopup/click监听)
 *   3. 通用 UI 更新函数 (updatePlayers/updateLogs/updateChat)
 *   4. 聊天发送逻辑
 *
 * 依赖：需要全局变量 roomId、playerId 已在页面中通过 Thymeleaf 注入
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
