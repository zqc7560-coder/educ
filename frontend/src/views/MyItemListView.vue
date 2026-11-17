<script setup>
/** 我的发布（M10）：全状态对自己透明 + 状态筛选 tabs；含论坛帖子分区（与失物件区分）。 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyItems, changeItemStatus, deleteItem } from '@/api/item'
import { listPosts, deletePost } from '@/api/forum'
import ItemCard from '@/components/ItemCard.vue'
import { fmtTime } from '@/utils/format'

const router = useRouter()
const section = ref('items')   // items=失物招领 | posts=论坛帖子
const tab = ref('all')
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

const POST_STATUS = { 0: ['待审核', 'warning'], 1: ['已发布', 'success'], 2: ['已下架', 'info'] }

async function load() {
  loading.value = true
  try {
    if (section.value === 'items') {
      const params = { pageNum: pageNum.value, pageSize: 12 }
      if (tab.value !== 'all') params.status = Number(tab.value)
      const p = await listMyItems(params)
      rows.value = p.list || []
      total.value = p.total || 0
    } else {
      const params = { mine: true, pageNum: pageNum.value, pageSize: 12 }
      if (tab.value !== 'all') params.status = Number(tab.value)
      const p = await listPosts(params)
      rows.value = p.list || []
      total.value = p.total || 0
    }
  } catch { rows.value = [] } finally { loading.value = false }
}
function switchTab() { pageNum.value = 1; load() }
function switchSection() { pageNum.value = 1; load() }
onMounted(load)

async function remove(it) {
  try {
    await ElMessageBox.confirm(`删除「${it.title}」？删除后进回收站。`, '删除', { type: 'warning' })
    await deleteItem(it.id)
    ElMessage.success('已删除')
    load()
  } catch { /* 取消/拦截器 toast */ }
}

async function removePost(p) {
  try {
    await ElMessageBox.confirm(`删除帖子《${p.title}》？`, '删除', { type: 'warning' })
    await deletePost(p.id)
    ElMessage.success('已删除')
    load()
  } catch { /* 取消/拦截器 toast */ }
}
</script>

<template>
  <div class="page" v-loading="loading">
    <el-radio-group v-model="section" class="sec" @change="switchSection">
      <el-radio-button value="items">失物招领</el-radio-button>
      <el-radio-button value="posts">论坛帖子</el-radio-button>
    </el-radio-group>
    <el-tabs v-model="tab" @tab-change="switchTab">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="待审核" name="0" />
      <el-tab-pane label="已发布" name="1" />
      <el-tab-pane label="已下架" name="2" />
    </el-tabs>

    <div v-if="section === 'items'" class="grid">
      <div v-for="it in rows" :key="it.id" class="cell">
        <ItemCard :item="it" show-status />
        <div class="ops">
          <el-button size="small" @click="router.push(`/user/items/${it.id}/edit`)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="remove(it)">删除</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !rows.length" description="还没有发布过信息" class="empty" />
    </div>

    <div v-else class="plist">
      <div v-for="p in rows" :key="p.id" class="prow">
        <div class="pmeta">
          <el-tag size="small" effect="plain">{{ p.boardName || '论坛' }}</el-tag>
          <el-tag size="small" :type="POST_STATUS[p.status]?.[1]">{{ POST_STATUS[p.status]?.[0] || p.status }}</el-tag>
          <el-link type="primary" class="ptitle" @click="router.push(`/forum/${p.id}`)">{{ p.title }}</el-link>
        </div>
        <div class="pinfo dim">{{ fmtTime(p.publishedAt || p.createdAt) }} · 阅读 {{ p.viewCount }} · 回复 {{ p.replyCount }} · 赞 {{ p.likeCount }}</div>
        <div class="ops">
          <el-button size="small" @click="router.push(`/forum/${p.id}/edit`)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="removePost(p)">删除</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !rows.length" description="还没有发过帖子" class="empty" />
    </div>

    <el-pagination v-if="total > 12" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="12" :current-page="pageNum"
                   @current-change="(n) => { pageNum = n; load() }" />
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; }
.sec { margin-bottom: 8px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; margin-top: 8px; min-height: 160px; }
.cell { display: flex; flex-direction: column; gap: 6px; }
.ops { display: flex; gap: 6px; justify-content: flex-end; }
.empty { grid-column: 1 / -1; }
.pager { margin-top: 16px; justify-content: center; }
.plist { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; min-height: 160px; }
.prow { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 12px 14px; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.pmeta { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 240px; }
.ptitle { font-weight: 600; }
.pinfo { font-size: 12px; }
.dim { color: #909399; }
</style>
