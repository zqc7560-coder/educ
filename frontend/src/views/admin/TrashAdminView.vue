<script setup>
/** 回收站（M10）：已删物品列表 + 复活（状态保持删除前原值）/彻底删除（物理删行）。 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/api/request'
import { fmtTime } from '@/utils/format'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const keyword = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 15 }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const p = await request.get('/items/trash', { params })
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

async function restore(row) {
  try {
    await request.post(`/items/${row.id}/restore`)
    ElMessage.success('已复活（保持删除前状态）')
    load()
  } catch { /* toast */ }
}
async function purge(row) {
  try {
    await ElMessageBox.confirm(`彻底删除「${row.title}」？物理删行不可恢复。`, '危险操作', { type: 'error', confirmButtonText: '确认彻底删除' })
    await request.delete(`/items/${row.id}/purge`)
    ElMessage.success('已彻底删除')
    load()
  } catch { /* 取消/toast */ }
}
</script>

<template>
  <div v-loading="loading">
    <h2>回收站</h2>
    <el-alert type="info" :closable="false" class="intro">
      <template #title>回收站收纳<b>已被删除（软删）的失物招领信息</b>——用户删除或治理删除的物品会进到这里，
      不会消失在大厅/详情之外的任何公开位置。在这里你可以：①<b>搜索找回误删件</b>（按标题）；
      ②<b>复活</b>——恢复到删除前的原始状态（待审/已发布/下架都原样还原，不会自动过审）；
      ③<b>彻底删除</b>——物理删行、不可恢复，用于清理确认无价值的旧数据。误删申诉、数据纠纷都靠这个页面处理。</template>
    </el-alert>
    <div class="bar">
      <el-input v-model="keyword" placeholder="搜标题" clearable style="width:220px"
                @keyup.enter="() => { pageNum = 1; load() }" @clear="() => { pageNum = 1; load() }" />
      <el-button type="primary" @click="() => { pageNum = 1; load() }">搜索</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ row.type === 'FOUND' ? '招领' : '失物' }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="userNickname" label="作者" width="120" />
      <el-table-column label="删除前进站时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" type="success" plain @click="restore(row)">复活</el-button>
          <el-button size="small" type="danger" @click="purge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="回收站是空的" />
    <el-pagination v-if="total > 15" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="15" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.intro { margin-bottom: 12px; }
h2 { margin: 0 0 14px; font-size: 19px; }
.bar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 14px; justify-content: center; }
</style>
