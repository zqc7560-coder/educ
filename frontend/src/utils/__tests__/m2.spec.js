/**
 * M2 前端契约测试：登录响应 → userStore 快照 → 守卫取态的流转语义。
 *
 * 与博客毕设 m2.spec 同源：store 是"登录态单一事实来源"，本测试钉三条契约：
 * 1. applyLogin 双写（内存 + localStorage 键 lf_token/lf_user），刷新水合不丢；
 * 2. 登录快照 user.studentNo/roles/perms 原样可读（菜单/按钮渲染依据）；
 * 3. logout 清内存清存储，localStorage 不留残余。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

// 内存 localStorage 替身（node 环境无 DOM；键名与被测模块内部常量一致）
function makeStorage() {
  const map = new Map()
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    _map: map
  }
}

describe('M2 userStore: 登录快照与登出清理', () => {
  let setActivePinia, createPinia, useUserStore
  let storage

  beforeEach(async () => {
    storage = makeStorage()
    vi.stubGlobal('localStorage', storage)
    ;({ setActivePinia, createPinia } = await import('pinia'))
    setActivePinia(createPinia())
    // 相对路径导入与 request.js/stores 同一实例（博客 M0 坑 #3：混用别名与
    // 相对路径会让 Vite 视为两个模块实例，localStorage stub 不共享）
    ;({ useUserStore } = await import('../../stores/user.js'))
  })

  const snap = {
    id: 9,
    studentNo: '2023508099',
    nickname: '测试号',
    avatar: null,
    roles: ['ROLE_STUDENT'],
    perms: []
  }

  it('applyLogin：token 进 store 与 localStorage，快照双写', async () => {
    const store = useUserStore()
    await store.applyLogin('jwt-token-abc', snap)

    expect(store.token).toBe('jwt-token-abc')
    expect(store.isLoggedIn).toBe(true)
    expect(storage._map.get('lf_token')).toBe('jwt-token-abc')
    expect(JSON.parse(storage._map.get('lf_user')).studentNo).toBe('2023508099')
  })

  it('登录后 user.roles 可读（isAdmin 判定不认 MANAGER）', async () => {
    const store = useUserStore()
    await store.applyLogin('t', { ...snap, roles: ['ROLE_STUDENT', 'ROLE_MANAGER'] })
    expect(store.user.roles).toContain('ROLE_MANAGER')
    expect(store.isAdmin).toBe(false)

    await store.applyLogin('t2', { ...snap, roles: ['ROLE_ADMIN'] })
    expect(store.isAdmin).toBe(true)
  })

  it('logout：内存与存储两清', async () => {
    const store = useUserStore()
    await store.applyLogin('jwt-x', snap)
    store.logout()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(storage._map.has('lf_token')).toBe(false)
    expect(storage._map.has('lf_user')).toBe(false)
  })

  it('新 store 实例从 localStorage 水合（刷新页面守卫免重登）', async () => {
    const store = useUserStore()
    await store.applyLogin('jwt-persist', snap)

    // 模拟页面刷新：换 pinia 实例重建 store，token 从存储水合
    setActivePinia(createPinia())
    const { useUserStore: again } = await import('../../stores/user.js')
    const revived = again()
    expect(revived.token).toBe('jwt-persist')
    expect(revived.isLoggedIn).toBe(true)
    expect(revived.user.studentNo).toBe('2023508099')
  })
})
