<script setup>
/** 操作日志（M10）：治理动作审计流水，action/target 过滤 + 分页。 */
import { ref, onMounted } from 'vue'
import { listOpLogs } from '@/api/admin'
import { fmtTime } from '@/utils/format'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const action = ref('')
const target = ref('')
const loading = ref(false)

const ACTIONS = ['ITEM_AUDIT_PASS', 'ITEM_AUDIT_REJECT', 'ITEM_OFFLINE', 'POST_AUDIT_PASS', 'POST_AUDIT_REJECT',
  'REPLY_BLOCK', 'USER_MUTE', 'USER_BAN', 'USER_UNBAN', 'USER_ROLES', 'WORD_ADD', 'WORD_DELETE',
  'CONFIG_UPDATE', 'ANNOUNCE_PUBLISH', 'ANNOUNCE_OFFLINE']

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 20 }
    if (action.value) params.action = action.value
    if (target.value.trim()) params.target = target.value.trim()
    const p = await listOpLogs(params)
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <h2>操作日志</h2>
    <div class="bar">
      <el-select v-model="action" placeholder="全部动作" clearable style="width:200px" @change="() => { pageNum = 1; load() }">
        <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
      </el-select>
      <el-input v-model="target" placeholder="对象（如 user:5 / word:12）" clearable style="width:230px"
                @keyup.enter="() => { pageNum = 1; load() }" @clear="() => { pageNum = 1; load() }" />
      <el-button type="primary" @click="() => { pageNum = 1; load() }">查询</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="userId" label="操作者" width="90" />
      <el-table-column prop="action" label="动作" width="170">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.action }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="target" label="对象" width="140" />
      <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="没有符合条件的审计记录" />
    <el-pagination v-if="total > 20" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="20" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
h2 { margin: 0 0 14px; font-size: 19px; }
.bar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 14px; justify-content: center; }
</style>
