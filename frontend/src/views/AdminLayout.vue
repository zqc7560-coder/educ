<script setup>
/**
 * 管理后台外壳（M10）：侧栏按权限点渲染（13 个权限点里后台相关的 8 个），
 * 路由级 roles 守卫在 router，这里做菜单级 perms 显隐——"这是管理员用的"
 * 入口对普通用户不可见（产品口径），后端每个口还有 perms 硬闸。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logout as logoutApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import BackBar from '@/components/BackBar.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const MENUS = [
  { path: '/admin', label: '数据看板', exact: true },
  { path: '/admin/audit', label: '审核队列' },
  { path: '/admin/arbitration', label: '认领仲裁', perm: 'claim:arbitrate' },
  { path: '/admin/announcements', label: '公告管理', perm: 'announce:manage' },
  { path: '/admin/users', label: '用户治理', perm: 'user:manage' },
  { path: '/admin/words', label: '敏感词库', perm: 'word:manage' },
  { path: '/admin/configs', label: '系统参数', perm: 'config:manage' },
  { path: '/admin/trash', label: '回收站', perm: 'item:audit' },
  { path: '/admin/oplogs', label: '操作日志', perm: 'oplog:view' }
]
const menus = computed(() => MENUS.filter((m) => !m.perm || (store.perms || []).includes(m.perm)))
const on = (m) => (m.exact ? route.path === m.path : route.path === m.path)

async function logout() {
  try { await logoutApi() } catch { /* 令牌可能已失效 */ }
  store.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">失物招领 · 管理台</div>
      <router-link v-for="m in menus" :key="m.path" :to="m.path" :class="['menu', { on: on(m) }]">
        {{ m.label }}
      </router-link>
      <div class="foot">
        <router-link to="/" class="back">← 回到前台</router-link>
        <el-button link size="small" @click="logout">退出</el-button>
      </div>
    </aside>
    <main class="body">
      <div class="back-slot">
        <BackBar v-if="route.path !== '/admin'" fallback="/admin" label="返回上一页或回到数据看板" />
      </div>
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell { min-height: 100vh; display: flex; background: #f5f7fa; }
.side { width: 200px; background: #001529; display: flex; flex-direction: column; padding: 14px 8px; position: sticky; top: 0; height: 100vh; }
.brand { color: #fff; font-weight: 700; font-size: 15px; padding: 6px 12px 16px; }
.menu { display: block; padding: 10px 14px; border-radius: 6px; color: #a6b3c4; text-decoration: none; font-size: 14px; margin-bottom: 2px; }
.menu:hover { background: rgba(255, 255, 255, .08); color: #fff; }
.menu.on { background: #409eff; color: #fff; }
.foot { margin-top: auto; display: flex; flex-direction: column; gap: 8px; padding: 10px 12px; }
.back { color: #a6b3c4; text-decoration: none; font-size: 13px; }
.body { flex: 1; min-width: 0; padding: 20px 24px; }
</style>
