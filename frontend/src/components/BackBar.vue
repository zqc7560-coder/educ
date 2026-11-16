<script setup>
/**
 * 通用返回条：有应用内历史则 router.back() 回上一页；
 * 直达/刷新丢失历史时退回 fallback（默认首页），永远有出路。
 * 挂在 SiteLayout（非首页）、UserCenterView、AdminLayout 三个壳上，
 * 覆盖全部子页面——修复"进了个人中心/详情页找不到返回入口"的体验缺陷。
 */
import { useRouter } from 'vue-router'

const props = defineProps({
  /** 无可回退历史时的兜底路径 */
  fallback: { type: String, default: '/' },
  /** 左侧说明文字（可选） */
  label: { type: String, default: '' }
})

const router = useRouter()

function goBack() {
  // vue-router 在 history.state.back 记录站内上一页；直达时为空
  if (window.history.state && window.history.state.back) {
    router.back()
  } else {
    router.push(props.fallback)
  }
}
</script>

<template>
  <div class="backbar">
    <el-button size="small" @click="goBack">← 返回上一页</el-button>
    <router-link v-if="fallback !== '/'" to="/" class="home-link">回到首页</router-link>
    <span v-if="label" class="tip">{{ label }}</span>
  </div>
</template>

<style scoped>
.backbar { display: flex; align-items: center; gap: 12px; margin: 0 0 12px; }
.home-link { color: #409eff; text-decoration: none; font-size: 13px; }
.home-link:hover { text-decoration: underline; }
.tip { color: #909399; font-size: 12px; }
</style>
