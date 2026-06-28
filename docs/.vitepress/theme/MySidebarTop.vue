<template>
  <div class="sidebar-brand">
    <h1 class="sidebar-title">MaDaoGame</h1>
    <p class="sidebar-subtitle">马刀游戏</p>
  </div>
</template>

<script setup>
// 侧边栏顶部品牌组件
</script>

<style scoped>
.sidebar-brand {
  padding: 16px 24px 12px;
  border-bottom: 1px solid var(--vp-c-divider);
  margin-bottom: 8px;
}

.sidebar-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--vp-c-brand-1);
  margin: 0;
  line-height: 1.4;
}

.sidebar-subtitle {
  font-size: 0.85rem;
  color: var(--vp-c-text-2);
  margin: 2px 0 0;
}
</style>
