<script setup>
/**
 * 全站公开顶栏（M10 实体版）——首页/详情/论坛/公告/用户中心共享的导航壳。
 * 只读 userStore（Pinia 单一事实源），登录态与未读红点都从 store/接口取，
 * 不各自碰 localStorage（守卫查存储、组件查 store 两套状态漂移的老坑）。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logout as logoutApi } from '@/api/auth'
import { activeBanners } from '@/api/common'
import { useUserStore } from '@/stores/user'
import BackBar from '@/components/BackBar.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const NAVS = [
  { path: '/', label: '失物大厅' },
  { path: '/forum', label: '校园论坛' },
  { path: '/announcements', label: '公告' }
]
const isActive = (path) => (path === '/' ? route.path === '/' : route.path.startsWith(path))

// 首页公告横幅（M6 active 口）：置顶优先最新一条；仅首页展示，失败静默
const banner = ref(null)
onMounted(async () => {
  if (route.path === '/') {
    try {
      const list = await activeBanners()
      banner.value = list && list.length ? list[0] : null
    } catch { /* 横幅是增强，拉不到不挡首屏 */ }
  }
})

async function logout() {
  try { await logoutApi() } catch { /* 令牌可能已失效，本地照清 */ }
  store.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
const goPublish = () => router.push('/user/items/new')
</script>

<template>
  <div class="site">
    <header class="topbar">
      <div class="wrap">
        <router-link to="/" class="brand">校园失物招领</router-link>
        <nav class="navs">
          <router-link v-for="n in NAVS" :key="n.path" :to="n.path"
                       :class="['nav', { on: isActive(n.path) }]">{{ n.label }}</router-link>
        </nav>
        <div class="right">
          <el-button v-if="store.isLoggedIn" type="primary" size="small" @click="goPublish">我要发布</el-button>
          <template v-if="store.isLoggedIn">
            <router-link to="/user" class="user-link">{{ store.user?.nickname || '我的中心' }}</router-link>
            <el-button link size="small" @click="logout">退出</el-button>
          </template>
          <el-button v-else type="primary" size="small" @click="router.push('/login')">登录 / 注册</el-button>
        </div>
      </div>
    </header>

    <div v-if="banner && route.path === '/'" class="banner">
      <div class="wrap banner-inner">
        <el-tag size="small" type="warning" effect="dark">公告</el-tag>
        <router-link to="/announcements" class="banner-title">{{ banner.title }}</router-link>
      </div>
    </div>

    <main class="main">
      <div v-if="route.path !== '/'" class="wrap back-slot">
        <BackBar label="返回上一页或回到失物大厅" />
      </div>
      <router-view />
    </main>

    <footer class="foot">
      <div class="wrap">校园失物招领系统 · 软件工程毕业设计</div>
    </footer>
  </div>
</template>

<style scoped>
.site { min-height: 100vh; display: flex; flex-direction: column; background: #f5f7fa; }
.wrap { max-width: 1080px; margin: 0 auto; width: 100%; }
.topbar { background: #fff; border-bottom: 1px solid #ebeef5; position: sticky; top: 0; z-index: 10; }
.topbar .wrap { display: flex; align-items: center; height: 56px; gap: 24px; }
.brand { font-weight: 700; font-size: 18px; color: #409eff; text-decoration: none; }
.navs { display: flex; gap: 4px; flex: 1; }
.nav { padding: 6px 14px; border-radius: 6px; color: #606266; text-decoration: none; font-size: 14px; }
.nav:hover { background: #f0f2f5; }
.nav.on { background: #ecf5ff; color: #409eff; font-weight: 600; }
.right { display: flex; align-items: center; gap: 10px; }
.user-link { color: #409eff; text-decoration: none; font-size: 14px; }
.banner { background: #fff7e6; border-bottom: 1px solid #ffe7ba; }
.banner-inner { display: flex; align-items: center; gap: 10px; padding: 8px 0; }
.banner-title { color: #b8860b; text-decoration: none; font-size: 14px; }
.main { flex: 1; }
.back-slot { padding-top: 14px; margin-bottom: -2px; }
.foot { background: #fff; border-top: 1px solid #ebeef5; color: #909399; font-size: 13px; }
.foot .wrap { padding: 16px 0; text-align: center; }
</style>
