<script setup>
/**
 * 审核队列（M10）：物品/帖子/回复三队列合一（M8 audit-queue 聚合口），
 * 处置回各域状态机口——本页面只是三张表的操作面板。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditQueue, pendingReplies, handleReply } from '@/api/admin'
import { changeItemStatus } from '@/api/item'
import { changePostStatus, pendingReports, keepReportedPost } from '@/api/forum'
import { fmtTime } from '@/utils/format'

const router = useRouter()
const data = ref(null)
const replies = ref([])
const reports = ref([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    data.value = await auditQueue(20)
    const p = await pendingReplies({ pageNum: 1, pageSize: 20 })
    replies.value = p.list || []
    const rp = await pendingReports({ pageNum: 1, pageSize: 20 })
    reports.value = rp.list || []
  } finally { loading.value = false }
}
onMounted(load)

async function act(fn, msg, reload = true) {
  try { await fn(); ElMessage.success(msg); if (reload) load() } catch { /* toast */ }
}
const itemPass = (it) => act(() => changeItemStatus(it.id, 1), '已通过并发布')
async function itemReject(it) {
  try {
    const { value } = await ElMessageBox.prompt(`驳回「${it.title}」的理由（作者可见）`, '驳回', { inputPlaceholder: '必填' })
    if (!value?.trim()) return ElMessage.warning('理由必填')
    await act(() => changeItemStatus(it.id, 2, value), '已驳回')
  } catch { /* 取消 */ }
}
const postPass = (p) => act(() => changePostStatus(p.id, 1), '帖子已通过')
async function postReject(p) {
  try {
    const { value } = await ElMessageBox.prompt(`驳回《${p.title}》的理由`, '驳回', { inputPlaceholder: '必填' })
    if (!value?.trim()) return ElMessage.warning('理由必填')
    await act(() => changePostStatus(p.id, 2, value), '已驳回')
  } catch { /* 取消 */ }
}
const replyPass = (r) => act(() => handleReply(r.id, 1), '回复已通过')
const rptKeep = (r) => act(() => keepReportedPost(r.postId), '已保留帖子并销单（举报人已收到回执）')
async function rptDown(r) {
  const { value } = await ElMessageBox.prompt(`下架《${r.postTitle}》的理由`, '举报下架', { inputPlaceholder: '必填' })
  await act(() => changePostStatus(r.postId, 2, value), '已下架并销举报单')
}
async function replyBlock(r) {
  try {
    const { value } = await ElMessageBox.prompt('屏蔽理由（作者可见）', '屏蔽回复', { inputPlaceholder: '必填' })
    if (!value?.trim()) return ElMessage.warning('理由必填')
    await act(() => handleReply(r.id, 2, value), '已屏蔽')
  } catch { /* 取消 */ }
}
</script>

<template>
  <div v-loading="loading">
    <h2>审核队列</h2>

    <div class="panel">
      <h3>待审物品（{{ data?.items?.total ?? 0 }}）</h3>
      <el-table :data="data?.items?.list || []" size="small">
        <el-table-column label="标题" min-width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push(`/items/${row.id}`)">{{ row.title }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.type === 'FOUND' ? '招领' : '失物' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="160">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="itemPass(row)">通过</el-button>
            <el-button size="small" type="danger" plain @click="itemReject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!(data?.items?.list || []).length" :image-size="50" description="物品队列清空了" />
    </div>

    <div class="panel">
      <h3>待审帖子（{{ data?.posts?.total ?? 0 }}）</h3>
      <el-table :data="data?.posts?.list || []" size="small">
        <el-table-column label="标题" min-width="200">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push(`/forum/${row.id}`)">{{ row.title }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="160">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="postPass(row)">通过</el-button>
            <el-button size="small" type="danger" plain @click="postReject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!(data?.posts?.list || []).length" :image-size="50" description="帖子队列清空了" />
    </div>

    <div class="panel">
      <h3>举报待处理（{{ reports.length }}）</h3>
      <el-table :data="reports" size="small">
        <el-table-column label="被举报帖" min-width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push(`/forum/${row.postId}`)">{{ row.postTitle }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="举报理由" min-width="160" show-overflow-tooltip />
        <el-table-column label="举报时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="220">
          <template #default="{ row }">
            <el-button size="small" @click="rptKeep(row)">审查保留</el-button>
            <el-button size="small" type="danger" plain @click="rptDown(row)">下架</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!reports.length" :image-size="50" description="没有待处理的举报" />
    </div>

    <div class="panel">
      <h3>待审回复（{{ replies.length }}）</h3>
      <el-table :data="replies" size="small">
        <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
        <el-table-column label="所在帖" width="90">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push(`/forum/${row.postId}`)">#{{ row.postId }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="160">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="replyPass(row)">通过</el-button>
            <el-button size="small" type="danger" plain @click="replyBlock(row)">屏蔽</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!replies.length" :image-size="50" description="回复队列清空了" />
    </div>
  </div>
</template>

<style scoped>
h2 { margin: 0 0 16px; font-size: 19px; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px 18px; margin-bottom: 14px; }
.panel h3 { margin: 0 0 10px; font-size: 15px; }
</style>
