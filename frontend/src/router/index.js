/**
 * 路由表（M0 骨架版）：公开站 / 登录 / 用户中心 / 管理后台 四棵路由树的
 * 最小占位，页面实体随 M2/M3/M5/M6/M10 逐模块补齐。
 *
 * 路由守卫规则（《开发计划.md》S3）：
 * - meta.requiresAuth 的路由未登录跳 /login?redirect=原地址；
 * - meta.roles 要求角色的路由不满足回首页（越权三防的前端一层，
 *   真正的防线在后端 @PreAuthorize，前端守卫只是体验优化）。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    component: () => import('@/views/SiteLayout.vue'),
    children: [
      { path: '', name: 'Home', component: () => import('@/views/HomeView.vue'), meta: { title: '首页' } },
      // 物品详情：公开路由（游客可访问，可见性由后端矩阵裁决）
      { path: 'items/:id', name: 'ItemDetail', component: () => import('@/views/ItemDetailView.vue'), meta: { title: '物品详情' } },
      // 论坛：列表与帖子详情（M5 落地实体页，先挂占位）
      { path: 'forum', name: 'ForumList', component: () => import('@/views/ForumListView.vue'), meta: { title: '校园论坛' } },
      { path: 'forum/:id', name: 'PostDetail', component: () => import('@/views/PostDetailView.vue'), meta: { title: '帖子详情' } },
      // 公告列表（M6）
      { path: 'announcements', name: 'Announcements', component: () => import('@/views/AnnouncementListView.vue'), meta: { title: '公告' } }
    ]
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', guestOnly: true }
  },
  {
    path: '/user',
    component: () => import('@/views/UserCenterView.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'UserCenter', component: () => import('@/views/UserHomeView.vue'), meta: { title: '用户中心' } },
      // 发布/编辑物品：new 与 :id/edit 复用同一组件（组件内按 route.params.id 区分）
      { path: 'items/new', name: 'ItemNew', component: () => import('@/views/ItemEditView.vue'), meta: { title: '发布信息', requiresAuth: true } },
      { path: 'items/:id/edit', name: 'ItemEdit', component: () => import('@/views/ItemEditView.vue'), meta: { title: '编辑信息', requiresAuth: true } },
      { path: 'my-items', name: 'MyItems', component: () => import('@/views/MyItemListView.vue'), meta: { title: '我的发布', requiresAuth: true } },
      { path: 'my-claims', name: 'MyClaims', component: () => import('@/views/MyClaimListView.vue'), meta: { title: '我的认领', requiresAuth: true } },
      { path: 'settings', name: 'UserSettings', component: () => import('@/views/UserSettingsView.vue'), meta: { title: '账号设置', requiresAuth: true } }
    ]
  },
  {
    // 后台布局壳：侧边菜单按权限点过滤渲染（M8 实体化），整棵树受角色守卫保护。
    // MANAGER（失物中心）与 ADMIN 都可进，菜单项再按权限点细分。
    path: '/admin',
    component: () => import('@/views/AdminLayout.vue'),
    meta: { title: '管理后台', requiresAuth: true, roles: ['ROLE_ADMIN', 'ROLE_MANAGER'] },
    children: [
      { path: '', name: 'AdminDashboard', component: () => import('@/views/admin/AdminDashboardView.vue'), meta: { title: '数据看板' } }
    ]
  },
  // 404 通配兜底（M10 全量页齐后仍保留——URL 手改错的常态出口）
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/NotFoundView.vue'), meta: { title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }) // 跳页回顶：列表→详情→返回的阅读惯性
})

/**
 * 全局前置守卫。三态判断刻意用 store.token（同步内存态）而非"等 me 接口回来"——
 * 骨架期登录态以本地令牌为准，令牌真实性的最终裁决在后端每个请求的过滤器里，
 * 守卫只是路由级体验，不做安全承诺。
 */
router.beforeEach((to) => {
  const store = useUserStore()
  document.title = (to.meta.title ? to.meta.title + ' - ' : '') + '校园失物招领'

  if (to.meta.requiresAuth && !store.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && store.token) {
    return { path: '/' } // 已登录不再进登录页
  }
  if (to.meta.roles && to.meta.roles.length) {
    // roles 判定用 store.user.roles（登录时随用户信息缓存）；缺信息时放行让后端兜底，
    // 避免"刷新页面 roles 还没恢复就误踢"的抖动
    const roles = store.user?.roles || []
    if (roles.length && !roles.some(r => to.meta.roles.includes(r))) {
      return { path: '/' }
    }
  }
  return true
})

export default router
