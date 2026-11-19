/**
 * 物品/字典接口封装（M10）—— 路径逐条对齐后端 ItemController/DictController。
 * 契约要点：详情 contact 仅登录可见（游客 null 不报错）；编辑必带 version（2004 冲突码
 * 由 request.js 挂在 err.code 上，页面据此引导刷新）；PATCH status 的 target/reason 走 query。
 */
import request from './request'

/** 列表：params {type,categoryId,campusId,buildingId,keyword,from,to,sort,mine,pageNum,pageSize} */
export function listItems(params) {
  return request.get('/items', { params })
}

/** 详情（自动计数；404 由拦截器 toast，页面处理 reject） */
export function fetchItem(id) {
  return request.get(`/items/${id}`)
}

/** 首页图片轮播帧（公开；仅已发布带图件，看图认物） */
export function fetchCarousel() {
  return request.get('/items/carousel')
}

/** 详情页相似推荐 Top5（同分类+时间窗+地点+文本重合度打分） */
export function fetchSimilarItems(id) {
  return request.get(`/items/${id}/similar`)
}

/** 置顶/取消置顶（治理，item:top） */
export function toggleItemTop(id) {
  return request.patch(`/items/${id}/top`)
}

/** 发布/编辑共用形状（编辑必带 version；type 后端锁死不可改，页面 new 时选、edit 时禁用） */
export function createItem(data) {
  return request.post('/items', data)
}
export function updateItem(id, data) {
  return request.put(`/items/${id}`, data)
}

/** 状态流转：下架需 reason（后端强校验）；target 1 发布 / 2 下架 */
export function changeItemStatus(id, target, reason) {
  return request.patch(`/items/${id}/status`, null, { params: { target, reason } })
}

export function deleteItem(id) {
  return request.delete(`/items/${id}`)
}

/** 我的发布（全状态对自己透明） */
export function listMyItems(params) {
  return request.get('/items/mine', { params })
}

/** 字典：分类（kind=ITEM/BOARD）与校区楼栋（平铺两级） */
export function fetchCategories(kind) {
  return request.get('/categories', { params: { kind } })
}
export function fetchAreas() {
  return request.get('/areas')
}
