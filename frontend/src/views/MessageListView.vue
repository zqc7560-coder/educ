<script setup>
/** 我的消息（M10）：分页 + 类型过滤 + 单条/全部已读；点击按 NOTIFY_META 跳转业务对象。 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listMyNotifications, markRead, markAllRead } from '@/api/common'
import { NOTIFY_META, fmtTime } from '@/utils/format'

const router = useRouter()
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const onlyUnread = ref(false)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const p = await listMyNotifications({ pageNum: pageNum.value, pageSize: 15, onlyUnread: onlyUnread.value })
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

async function open(n) {
  if (!n.readFlag) {
    try { await markRead(n.id); n.readFlag = 1 } catch { /* toast */ }
  }
  const to = (NOTIFY_META[n.bizType] || NOTIFY_META.SYSTEM).to(n.bizId)
  if (to) router.push(to)
}
async function all() {
  try {
    await markAllRead()
    ElMessage.success('全部已读')
    load()
  } catch { /* toast */ }
}
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="bar">
      <el-checkbox v-model="onlyUnread" @change="() => { pageNum = 1; load() }">只看未读</el-checkbox>
      <el-button size="small" link type="primary" @click="all">全部标为已读</el-button>
    </div>
    <div class="list">
      <div v-for="n in rows" :key="n.id" :class="['row', { unread: !n.readFlag }]" @click="open(n)">
        <div class="line1">
          <el-tag size="small" effect="plain">{{ (NOTIFY_META[n.bizType] || {}).label || n.bizType }}</el-tag>
          <b>{{ n.title }}</b>
          <span class="time">{{ fmtTime(n.createdAt) }}</span>
        </div>
        <div class="line2">{{ n.content }}</div>
      </div>
      <el-empty v-if="!loading && !rows.length" description="暂无消息" />
    </div>
    <el-pagination v-if="total > 15" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="15" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.row { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 12px 14px; cursor: pointer; }
.row:hover { border-color: #409eff; }
.row.unread { border-left: 3px solid #409eff; }
.line1 { display: flex; align-items: center; gap: 8px; }
.line1 b { color: #303133; font-size: 14px; }
.time { margin-left: auto; color: #909399; font-size: 12px; }
.line2 { color: #606266; font-size: 13px; margin-top: 6px; }
.pager { margin-top: 14px; justify-content: center; }
</style>
