<script setup>
/** 我的认领（M10）：申请人视角全状态透明；被拒/已关闭单给"申请仲裁"申诉通道（M4 3→4）。 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listMyClaims, applyArbitration } from '@/api/claim'
import { CLAIM_STATUS, fmtTime } from '@/utils/format'

const router = useRouter()
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const p = await listMyClaims({ pageNum: pageNum.value, pageSize: 10 })
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

async function arb(row) {
  try {
    await applyArbitration(row.id)
    ElMessage.success('已提交仲裁申请，管理员将依据双方证据裁决')
    load()
  } catch { /* toast */ }
}
</script>

<template>
  <div class="page" v-loading="loading">
    <el-table :data="rows" size="small">
      <el-table-column label="物品" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="router.push(`/items/${row.itemId}`)">查看物品 #{{ row.itemId }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="answer" label="我的答案" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="(CLAIM_STATUS[row.status] || {}).type" size="small">{{ (CLAIM_STATUS[row.status] || {}).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="拒绝理由" min-width="140">
        <template #default="{ row }">{{ row.status === 3 ? row.rejectReason : '—' }}</template>
      </el-table-column>
      <el-table-column label="申请时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="[0, 1, 3].includes(row.status)" size="small" link type="warning" @click="arb(row)">申请仲裁</el-button>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="还没有提交过认领申请" />
    <el-pagination v-if="total > 10" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="10" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; }
.dim { color: #c0c4cc; }
.pager { margin-top: 14px; justify-content: center; }
</style>
