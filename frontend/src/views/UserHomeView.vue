<script setup>
/** 用户中心总览（M10）：四入口卡 + 最近发布速览。 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMyItems } from '@/api/item'
import { unreadCount } from '@/api/common'
import { useUserStore } from '@/stores/user'
import { relTime } from '@/utils/format'

const router = useRouter()
const store = useUserStore()
const recent = ref([])
const unread = ref(0)

onMounted(async () => {
  try {
    const p = await listMyItems({ pageNum: 1, pageSize: 5 })
    recent.value = p.list || []
  } catch { /* 静默：总览页失败不弹 */ }
  try { unread.value = (await unreadCount()).count } catch { /* 同上 */ }
})

const QUICK = [
  { label: '我的发布', path: '/user/my-items' },
  { label: '我的认领', path: '/user/my-claims' },
  { label: '我的收藏', path: '/user/favorites' },
  { label: '我的消息', path: '/user/messages', badge: true }
]
</script>

<template>
  <div class="page">
    <div class="quick">
      <div v-for="q in QUICK" :key="q.path" class="q" @click="router.push(q.path)">
        <span>{{ q.label }}</span>
        <el-badge v-if="q.badge && unread" :value="unread" />
      </div>
    </div>

    <div class="panel">
      <h3>最近发布</h3>
      <div v-for="it in recent" :key="it.id" class="line" @click="router.push(`/items/${it.id}`)">
        <el-tag size="small" :type="it.type === 'FOUND' ? 'success' : 'danger'">{{ it.type === 'FOUND' ? '招领' : '失物' }}</el-tag>
        <span class="t">{{ it.title }}</span>
        <span class="dim">{{ relTime(it.createdAt) }}</span>
      </div>
      <el-empty v-if="!recent.length" :image-size="60" description="还没发布过信息">
        <el-button type="primary" size="small" @click="router.push('/user/items/new')">去发布</el-button>
      </el-empty>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; }
.quick { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.q { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 18px 14px; font-size: 15px; cursor: pointer; display: flex; align-items: center; justify-content: space-between; }
.q:hover { border-color: #409eff; color: #409eff; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px 20px; }
h3 { margin: 0 0 10px; font-size: 15px; }
.line { display: flex; align-items: center; gap: 10px; padding: 8px 4px; border-bottom: 1px dashed #f0f2f5; cursor: pointer; }
.line:hover .t { color: #409eff; }
.t { color: #303133; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dim { color: #909399; font-size: 12px; }
</style>
