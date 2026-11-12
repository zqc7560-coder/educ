/**
 * 路由表（M10 实体版，替换 M0 骨架占位）。
 *
 * 三棵路由树：公开站（SiteLayout）/ 用户中心（UserCenterView 侧栏壳）/ 管理后台
 * （AdminLayout，roles 守卫）。每条路由的 component 现在都指向真实页面。
 *
 * 守卫规则不变（requiresAuth / guestOnly / roles）；roles 判定用登录时缓存的
 * store.user.roles，缺信息时放行让后端 @PreAuthorize 兜底，避免刷新抖动误踢。
 * 管理后台的"菜单项级"权限（word/config/user/oplog 仅 ADMIN）由 AdminLayout
 * 按 perms 过滤渲染，路由级只保证"能进后台"（MANAGER+ADMIN 都可进）。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    component: () => import('@/views/SiteLayout.vue'),
    children: [
      { path: '', name: 'Home', component: () => import('@/views/HomeView.vue'), meta: { title: '首页' } },
      { path: 'items/:id', name: 'ItemDetail', component: () => import('@/views/ItemDetailView.vue'), meta: { title: '物品详情' } },
      { path: 'forum', name: 'ForumList', component: () => import('@/views/ForumListView.vue'), meta: { title: '校园论坛' } },
      { path: 'forum/new', name: 'PostNew', component: () => import('@/views/PostEditView.vue'), meta: { title: '发布帖子', requiresAuth: true } },
      { path: 'forum/:id/edit', name: 'PostEdit', component: () => import('@/views/PostEditView.vue'), meta: { title: '编辑帖子', requiresAuth: true } },
      { path: 'forum/:id', name: 'PostDetail', component: () => import('@/views/PostDetailView.vue'), meta: { title: '帖子详情' } },
      { path: 'announcements', name: 'Announcements', component: () => import('@/views/AnnouncementListView.vue'), meta: { title: '公告' } }
    ]
  },
  {
    path: '/login', name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', guestOnly: true }
  },
  {
    path: '/user',
    component: () => import('@/views/UserCenterView.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'UserCenter', component: () => import('@/views/UserHomeView.vue'), meta: { title: '用户中心' } },
      { path: 'items/new', name: 'ItemNew', component: () => import('@/views/ItemEditView.vue'), meta: { title: '发布信息', requiresAuth: true } },
      { path: 'items/:id/edit', name: 'ItemEdit', component: () => import('@/views/ItemEditView.vue'), meta: { title: '编辑信息', requiresAuth: true } },
      { path: 'my-items', name: 'MyItems', component: () => import('@/views/MyItemListView.vue'), meta: { title: '我的发布', requiresAuth: true } },
      { path: 'my-claims', name: 'MyClaims', component: () => import('@/views/MyClaimListView.vue'), meta: { title: '我的认领', requiresAuth: true } },
      { path: 'messages', name: 'MyMessages', component: () => import('@/views/MessageListView.vue'), meta: { title: '我的消息', requiresAuth: true } },
      { path: 'favorites', name: 'MyFavorites', component: () => import('@/views/FavoriteListView.vue'), meta: { title: '我的收藏', requiresAuth: true } },
      { path: 'settings', name: 'UserSettings', component: () => import('@/views/UserSettingsView.vue'), meta: { title: '账号设置', requiresAuth: true } }
    ]
  },
  {
    path: '/admin',
    component: () => import('@/views/AdminLayout.vue'),
    meta: { title: '管理后台', requiresAuth: true, roles: ['ROLE_ADMIN', 'ROLE_MANAGER'] },
    children: [
      { path: '', name: 'AdminDashboard', component: () => import('@/views/admin/AdminDashboardView.vue'), meta: { title: '数据看板' } },
      { path: 'audit', name: 'AdminAudit', component: () => import('@/views/admin/AuditQueueView.vue'), meta: { title: '审核队列' } },
      { path: 'arbitration', name: 'AdminArbitration', component: () => import('@/views/admin/ArbitrationView.vue'), meta: { title: '认领仲裁' } },
      { path: 'announcements', name: 'AdminAnnounce', component: () => import('@/views/admin/AnnounceAdminView.vue'), meta: { title: '公告管理' } },
      { path: 'words', name: 'AdminWords', component: () => import('@/views/admin/WordsAdminView.vue'), meta: { title: '敏感词库', roles: ['ROLE_ADMIN'] } },
      { path: 'users', name: 'AdminUsers', component: () => import('@/views/admin/UsersAdminView.vue'), meta: { title: '用户治理', roles: ['ROLE_ADMIN'] } },
      { path: 'configs', name: 'AdminConfigs', component: () => import('@/views/admin/ConfigsAdminView.vue'), meta: { title: '系统参数', roles: ['ROLE_ADMIN'] } },
      { path: 'trash', name: 'AdminTrash', component: () => import('@/views/admin/TrashAdminView.vue'), meta: { title: '回收站' } },
      { path: 'oplogs', name: 'AdminOpLogs', component: () => import('@/views/admin/OpLogsView.vue'), meta: { title: '操作日志', roles: ['ROLE_ADMIN'] } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/NotFoundView.vue'), meta: { title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const store = useUserStore()
  document.title = (to.meta.title ? to.meta.title + ' - ' : '') + '校园失物招领'

  if (to.meta.requiresAuth && !store.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && store.token) {
    return { path: '/' }
  }
  if (to.meta.roles && to.meta.roles.length) {
    const roles = store.user?.roles || []
    if (roles.length && !roles.some((r) => to.meta.roles.includes(r))) {
      return { path: '/' }   // 越权回首页（后端仍是最终防线）
    }
  }
  return true
})

export default router
