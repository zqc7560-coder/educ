<script setup>
/** 认领仲裁（M10）：status=4 队列 + 裁决对话框（判归→物品下架；驳回→维持下架理由）。 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listArbitration, arbitrate } from '@/api/claim'
import { fmtTime } from '@/utils/format'

const router = useRouter()
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

const dlg = ref(false)
const cur = ref(null)
const form = ref({ award: true, judgment: '' })

async function load() {
  loading.value = true
  try {
    const p = await listArbitration({ pageNum: pageNum.value, pageSize: 10 })
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

function openJudge(row) {
  cur.value = row
  form.value = { award: true, judgment: '' }
  dlg.value = true
}
async function judge() {
  if (!form.value.judgment.trim()) return ElMessage.warning('裁决理由必填')
  try {
    await arbitrate(cur.value.id, form.value.award, form.value.judgment.trim())
    ElMessage.success('裁决已生效')
    dlg.value = false
    load()
  } catch { /* toast */ }
}
</script>

<template>
  <div v-loading="loading">
    <h2>认领仲裁</h2>
    <el-table :data="rows" size="small">
      <el-table-column label="物品" width="90">
        <template #default="{ row }">
          <el-link type="primary" @click="router.push(`/items/${row.itemId}`)">#{{ row.itemId }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="claimantNickname" label="申请人" width="110" />
      <el-table-column prop="answer" label="核验答案" min-width="160" show-overflow-tooltip />
      <el-table-column prop="evidenceNote" label="凭证补充" min-width="140" show-overflow-tooltip />
      <el-table-column label="进入仲裁" width="150">
        <template #default="{ row }">{{ fmtTime(row.handledAt || row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="裁决" width="110">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openJudge(row)">裁决</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="没有待仲裁的申请" />
    <el-pagination v-if="total > 10" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="10" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />

    <el-dialog v-model="dlg" title="仲裁裁决" width="480px">
      <el-alert type="info" :closable="false" class="tip"
                :title="cur ? `物品 #${cur.itemId} · 申请人 ${cur.claimantNickname} · 答案：${cur.answer}` : ''" />
      <el-radio-group v-model="form.award" class="rg">
        <el-radio :value="true">判归申请人（物品下架为"仲裁判归"）</el-radio>
        <el-radio :value="false">维持拒绝（驳回申请人）</el-radio>
      </el-radio-group>
      <el-input v-model="form.judgment" type="textarea" :rows="3" maxlength="500"
                placeholder="裁决理由（双方可见，将写入仲裁日志）" />
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="judge">提交裁决</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
h2 { margin: 0 0 14px; font-size: 19px; }
.pager { margin-top: 14px; justify-content: center; }
.tip { margin-bottom: 12px; }
.rg { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 12px; }
</style>
