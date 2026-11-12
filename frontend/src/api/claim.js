/** 认领接口封装（M10）—— 对齐 ClaimController：申请挂物品下，处置挂认领单下。 */
import request from './request'

/** 提交认领申请（仅 FOUND 已发布件；自己发布的后端 3005 拒） */
export function applyClaim(itemId, data) {
  return request.post(`/items/${itemId}/claims`, data)
}

/** 某物品的认领单列表（发布者核验视图/仲裁管理员；无关者 403 由拦截器 toast） */
export function listClaimsForItem(itemId) {
  return request.get(`/items/${itemId}/claims`)
}

/** 我的认领（申请人视角全透明） */
export function listMyClaims(params) {
  return request.get('/claims/mine', { params })
}

export function verifyClaim(id, reason) {
  return request.post(`/claims/${id}/verify`, { reason })
}
export function rejectClaim(id, reason) {
  return request.post(`/claims/${id}/reject`, { reason })
}
export function confirmReturn(id) {
  return request.post(`/claims/${id}/confirm`)
}
export function applyArbitration(id) {
  return request.post(`/claims/${id}/arbitrate-apply`)
}

/** 仲裁口（claim:arbitrate） */
export function arbitrate(id, awardToClaimant, judgment) {
  return request.post(`/admin/claims/${id}/arbitrate`, { awardToClaimant, judgment })
}
export function listArbitration(params) {
  return request.get('/admin/claims/arbitration', { params })
}
