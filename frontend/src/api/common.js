/** 公告 + 消息 + 收藏 + 上传接口封装（M10）。 */
import request from './request'

// ---------- 公告（读者公开 / 管理 announce:manage） ----------
export function listAnnouncements(params) {
  return request.get('/announcements', { params })
}
export function activeBanners() {
  return request.get('/announcements/active')
}
export function fetchAnnouncement(id) {
  return request.get(`/announcements/${id}`)
}
export function adminListAnnouncements(params) {
  return request.get('/admin/announcements', { params })
}
export function createAnnouncement(data) {
  return request.post('/admin/announcements', data)
}
export function updateAnnouncement(id, data) {
  return request.put(`/admin/announcements/${id}`, data)
}
export function transitionAnnouncement(id, target) {
  return request.patch(`/admin/announcements/${id}/status`, { target })
}
export function toggleAnnouncementTop(id) {
  return request.post(`/admin/announcements/${id}/top`)
}
export function deleteAnnouncement(id) {
  return request.delete(`/admin/announcements/${id}`)
}

// ---------- 站内消息 ----------
export function listMyNotifications(params) {
  return request.get('/notifications/mine', { params })
}
export function unreadCount() {
  return request.get('/notifications/unread-count')
}
export function markRead(id) {
  return request.post(`/notifications/${id}/read`)
}
export function markAllRead() {
  return request.post('/notifications/read-all')
}
export function broadcast(data) {
  return request.post('/admin/notifications/broadcast', data)
}

// ---------- 收藏 ----------
export function toggleFavorite(targetType, targetId) {
  return request.post('/favorites/toggle', { targetType, targetId })
}
export function listFavorites(type) {
  return request.get('/favorites', { params: { type } })
}

// ---------- 图片上传（multipart；scene=items/forum/announcements） ----------
export function uploadImages(files, scene) {
  const fd = new FormData()
  files.forEach((f) => fd.append('files', f))
  return request.post('/files/images', fd, { params: { scene } })
}
