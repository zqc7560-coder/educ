/**
 * 论坛接口封装（M10）—— 对齐 ForumController：帖子/两级回复树/点赞/举报。
 * 字典口（板块=category kind=BOARD）不在这里——分类接口归 item.js 的
 * fetchCategories(kind)，避免同一 URL 在两个模块各封一遍（口径漂移源）。
 */
import request from './request'

export function listPosts(params) {
  // {boardId,keyword,status,mine,sort,pageNum,pageSize}
  return request.get('/forum/posts', { params })
}
/** 帖详情（countView=false 用于收藏页/编辑页等非浏览场景，不计浏览量） */
export function fetchPost(id, { countView = true } = {}) {
  return request.get(`/forum/posts/${id}`, countView ? {} : { params: { pv: 0 } })
}
export function createPost(data) {
  return request.post('/forum/posts', data)
}
export function updatePost(id, data) {
  return request.put(`/forum/posts/${id}`, data)
}
export function deletePost(id) {
  return request.delete(`/forum/posts/${id}`)
}
export function changePostStatus(id, target, reason) {
  // 必须恒发 JSON 对象：axios 对 null data 会按默认头发成 x-www-form-urlencoded，
  // 后端 @RequestBody 解析器对该接口直接 415→500（审核"通过"按钮实测炸点）
  return request.patch(`/forum/posts/${id}/status`, { reason: reason ?? undefined }, { params: { target } })
}
export function togglePostTop(id) {
  return request.post(`/forum/posts/${id}/top`)
}
export function pendingReports(params) {
  return request.get('/forum/reports/pending', { params })
}
export function keepReportedPost(id) {
  return request.post(`/forum/posts/${id}/reports/keep`)
}

export function fetchReplyTree(postId) {
  return request.get(`/forum/posts/${postId}/replies`)
}
/** data: {content, parentId?, replyToUserId?}（两级封顶：parentId 只许指一级回复） */
export function replyPost(postId, data) {
  return request.post(`/forum/posts/${postId}/replies`, data)
}
export function deleteReply(replyId) {
  return request.delete(`/forum/replies/${replyId}`)
}

export function likePost(id) {
  return request.post(`/forum/posts/${id}/like`)
}
export function unlikePost(id) {
  return request.delete(`/forum/posts/${id}/like`)
}
export function reportPost(id, reason) {
  return request.post(`/forum/posts/${id}/report`, { reason })
}
