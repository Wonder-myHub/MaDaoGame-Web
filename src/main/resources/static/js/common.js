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
 * 切换规则弹窗的显示/隐藏状态
 * 使用 sessionStorage 持久化状态，页面刷新后不丢失
 */
function toggleRules() {
    var popup = document.getElementById('rulesPopup');
    popup.classList.toggle('show');
    sessionStorage.setItem(RULES_KEY, popup.classList.contains('show'));
}

/**
 * 从 sessionStorage 恢复规则弹窗的显示状态
 * 在每次 AJAX 刷新后调用，确保弹窗状态不被重置
 */
function restoreRulesPopup() {
    var popup = document.getElementById('rulesPopup');
    if (popup && sessionStorage.getItem(RULES_KEY) === 'true') {
        popup.classList.add('show');
    }
}

// 全局点击监听：点击弹窗外部区域时自动关闭弹窗
window.addEventListener('click', function(e) {
    var container = document.querySelector('.rules-container');
    if (!container.contains(e.target)) {
        var popup = document.getElementById('rulesPopup');
        popup.classList.remove('show');
        sessionStorage.setItem(RULES_KEY, false);
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

        html += '<div class="player-card' + currentClass + deadClass + '">';
        html += '<p><strong>' + escapeHtml(p.name) + '</strong>' + (isMe ? '（我）' : '') + '</p>';
        html += '<p>\u{1F434} ' + (p.horse ? '有' : '无') + '</p>';
        html += '<p>\u{1F52A} ' + (p.knife ? '有' : '无') + '</p>';
        html += '<p>\u2764\uFE0F ' + (p.hp > 0 ? p.hp : 0) + '</p>';
        html += '<p>\u{1F4CD} ' + escapeHtml(p.location) + '</p>';
        if (p.steps > 0) html += '<p>\u{1F463} ' + p.steps + '</p>';
        if (p.buff && p.hp > 0) html += '<p class="blood-buff">\u{1F525} 血祭</p>';
        html += '</div>';
    }
    container.innerHTML = html;
}

/**
 * 更新操作记录列表
 * 优化：仅在列表长度变化时重新渲染，避免不必要的 DOM 操作
 *
 * @param {Array<string>} logs - 操作记录字符串数组
 */
function updateLogs(logs) {
    if (!logs) return;
    var logList = document.getElementById('logList');
    if (!logList) return;
    if (logList.children.length !== logs.length) {
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
    var needUpdate = chatList.children.length !== messages.length;
    if (!needUpdate) {
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
 * 发送消息后立即刷新聊天列表
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
            await fetch('/api/chat/' + roomId + '/' + playerId, { method: 'POST', body: formData });
            input.value = '';
            var res = await fetch('/api/room/' + roomId + '?playerId=' + playerId);
            var data = await res.json();
            updateChat(data.chatMessages);
        } catch (err) {
            console.error(err);
        }
    });
}
