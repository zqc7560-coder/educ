/**
 * 共享格式化工具（M10）。
 * 时间戳统一收口：后端 LocalDateTime 序列化为 ISO（含 T），展示一律换空格截秒——
 * 写进共用 fmt 防逐列手写漂移（web 技能回归纪律）；无值回显占位符。
 */
export function fmtTime(iso) {
  if (!iso) return '—'
  return String(iso).replace('T', ' ').slice(0, 16)
}

/** 相对时间（列表页友好）：刚刚 / N 分钟前 / N 小时前 / 昨天 / 日期 */
export function relTime(iso) {
  if (!iso) return '—'
  const t = new Date(String(iso).replace('T', ' ').replace(/-/g, '/'))
  const diff = Date.now() - t.getTime()
  if (Number.isNaN(diff)) return fmtTime(iso)
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  if (h < 48) return '昨天'
  return fmtTime(iso).slice(5, 10)
}

/** 认领单状态 → {text, type}（Element tag 颜色语义） */
export const CLAIM_STATUS = {
  0: { text: '待核验', type: 'warning' },
  1: { text: '约定交接', type: 'primary' },
  2: { text: '已完成', type: 'success' },
  3: { text: '已拒绝', type: 'danger' },
  4: { text: '仲裁中', type: 'info' },
  5: { text: '已关闭', type: 'info' }
}

/** 内容状态（物品/帖子/回复/公告共用三态命名，公告 0 叫草稿） */
export function statusTag(status, kind = 'ITEM') {
  if (kind === 'ANNOUNCE') {
    return { 0: { text: '草稿', type: 'info' }, 1: { text: '已发布', type: 'success' }, 2: { text: '已下架', type: 'danger' } }[status] || { text: '?', type: 'info' }
  }
  return { 0: { text: '待审核', type: 'warning' }, 1: { text: '已发布', type: 'success' }, 2: { text: '已下架', type: 'danger' } }[status] || { text: '?', type: 'info' }
}

/** 消息 biz_type → 中文 + 跳转（前端映射表对齐后端 NotifyType，新类型必须双改） */
export const NOTIFY_META = {
  ITEM_AUDIT: { label: '审核结果', to: (bizId) => `/items/${bizId}` },
  CLAIM_NEW: { label: '新认领申请', to: (bizId) => `/items/${bizId}` },
  CLAIM_RESULT: { label: '认领进展', to: (bizId) => '/user/my-claims' },
  RETURN_CONFIRM: { label: '归还确认', to: (bizId) => '/user/my-claims' },
  MATCH: { label: '匹配推荐', to: (bizId) => `/items/${bizId}` },
  REPLY: { label: '回复/@我', to: (bizId) => `/forum/${bizId}` },
  LIKE: { label: '帖子被赞', to: (bizId) => `/forum/${bizId}` },
  POST_AUDIT: { label: '帖子审核', to: (bizId) => `/forum/${bizId}` },
  SYSTEM: { label: '系统通知', to: () => null }
}

/** 图片 URL 安全化：只认站内 /uploads 直出（后端已拦外链，这里防渲染面 XSS 属性注入） */
export function safeSrc(u) {
  return typeof u === 'string' && u.startsWith('/uploads/') ? u : ''
}
