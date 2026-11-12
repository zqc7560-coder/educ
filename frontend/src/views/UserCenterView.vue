<script setup>
/** 用户中心外壳（M10）：左侧功能菜单 + router-view。 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const store = useUserStore()

const MENUS = [
  { path: '/user', label: '总览', exact: true },
  { path: '/user/my-items', label: '我的发布' },
  { path: '/user/my-claims', label: '我的认领' },
  { path: '/user/favorites', label: '我的收藏' },
  { path: '/user/messages', label: '我的消息' },
  { path: '/user/settings', label: '账号设置' },
  { path: '/admin', label: '管理后台', admin: true }
]
const on = (m) => (m.exact ? route.path === m.path : route.path.startsWith(m.path))
// 管理后台入口按角色显隐（后端路由守卫仍兜底；藏入口是产品口径：普通用户不该看见）
const visible = computed(() =>
  MENUS.filter((m) => !m.admin || (store.user?.roles || []).some((r) => ['ROLE_ADMIN', 'ROLE_MANAGER'].includes(r))))
</script>

<template>
  <div class="shell wrap">
    <aside class="side">
      <div class="who">
        <el-avatar :size="46" :src="store.user?.avatar || undefined">{{ (store.user?.nickname || '我')[0] }}</el-avatar>
        <div>
          <div class="nick">{{ store.user?.nickname }}</div>
          <div class="no">{{ store.user?.studentNo }}</div>
        </div>
      </div>
      <router-link v-for="m in visible" :key="m.path" :to="m.path"
                   :class="['menu', { on: on(m) }]">{{ m.label }}</router-link>
    </aside>
    <section class="body"><router-view /></section>
  </div>
</template>

<style scoped>
.wrap { max-width: 1080px; margin: 0 auto; padding: 20px 16px 40px; }
.shell { display: flex; gap: 18px; align-items: flex-start; }
.side { width: 190px; background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px 10px; position: sticky; top: 76px; display: flex; flex-direction: column; gap: 2px; }
.who { display: flex; gap: 10px; align-items: center; padding: 0 8px 12px; border-bottom: 1px solid #f0f2f5; margin-bottom: 8px; }
.nick { font-weight: 600; font-size: 14px; }
.no { color: #909399; font-size: 12px; }
.menu { padding: 8px 12px; border-radius: 6px; color: #606266; text-decoration: none; font-size: 14px; }
.menu:hover { background: #f5f7fa; }
.menu.on { background: #ecf5ff; color: #409eff; font-weight: 600; }
.body { flex: 1; min-width: 0; }
</style>
