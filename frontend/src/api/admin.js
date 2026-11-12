/** 管理端接口封装（M10）—— 对齐 AdminGovernController/AdminUserController/ClaimController 仲裁口。 */
import request from './request'

/** 审核队列三合一（只读聚合；处置回各域状态机口） */
export function auditQueue(size) {
  return request.get('/admin/audit-queue', { params: { size } })
}
export function pendingReplies(params) {
  return request.get('/admin/replies/pending', { params })
}
export function handleReply(id, target, reason) {
  return request.post(`/admin/replies/${id}/handle`, { target, reason })
}

// ---------- 敏感词 ----------
export function listWords(params) {
  return request.get('/admin/words', { params })
}
export function addWord(word) {
  return request.post('/admin/words', { word })
}
export function deleteWord(id) {
  return request.delete(`/admin/words/${id}`)
}

// ---------- 系统参数 ----------
export function listConfigs() {
  return request.get('/admin/configs')
}
export function updateConfig(key, value) {
  return request.put(`/admin/configs/${key}`, { value })
}
export function clearCache() {
  return request.delete('/admin/cache')
}

// ---------- 看板与日志 ----------
export function statsOverview() {
  return request.get('/admin/stats/overview')
}
export function listOpLogs(params) {
  return request.get('/admin/oplogs', { params })
}

// ---------- 用户治理 ----------
export function adminListUsers(params) {
  return request.get('/admin/users', { params })
}
/** status: 1 正常 / 0 封禁 / 2 禁言 */
export function adminSetUserStatus(id, status) {
  return request.patch(`/admin/users/${id}/status`, null, { params: { status } })
}
export function adminAssignRoles(id, roleIds) {
  return request.post(`/admin/users/${id}/roles`, { roleIds })
}
