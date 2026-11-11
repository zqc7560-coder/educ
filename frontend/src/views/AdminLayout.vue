<script setup>
/**
 * 后台布局 —— M8/M10 落地的布局壳（骨架占位）。
 * 侧边菜单按权限点渲染。本文件 M0 先提供最小壳（router-view/占位文案），
 * 实体在对应模块完成时替换，路由地址自 M0 起保持稳定。
 */
</script>

<template>
  <div class="placeholder">
    <h2>后台布局</h2>
    <p>侧边菜单按权限点渲染</p>
    <p class="tag">（M8/M10 落地，骨架占位）</p>
    <router-view v-if="hasChild" />
  </div>
</template>

<script>
export default {
  computed: {
    // SiteLayout 无 children 时 router-view 渲染自身会递归，用路由表判断
    hasChild() {
      return this.$route.matched.some((r) => r.children && r.children.length)
    }
  }
}
</script>

<style scoped>
.placeholder { padding: 40px; text-align: center; color: #606266; }
.tag { color: #909399; font-size: 13px; }
</style>
