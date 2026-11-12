<script setup>
/** 敏感词库（M10）：分页 + 加词 + 删词（写后拦截即时生效=M8 链）。 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listWords, addWord, deleteWord } from '@/api/admin'
import { fmtTime } from '@/utils/format'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const keyword = ref('')
const newWord = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 20 }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const p = await listWords(params)
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

async function add() {
  if (!newWord.value.trim()) return ElMessage.warning('请输入要添加的词')
  try {
    await addWord(newWord.value.trim())
    ElMessage.success('已添加，拦截即时生效')
    newWord.value = ''
    load()
  } catch { /* 重复/超长 toast */ }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除敏感词「${row.word}」？删除后含该词内容立即放行。`, '删除', { type: 'warning' })
    await deleteWord(row.id)
    ElMessage.success('已删除')
    load()
  } catch { /* 取消 */ }
}
</script>

<template>
  <div v-loading="loading">
    <h2>敏感词库</h2>
    <div class="bar">
      <el-input v-model="newWord" placeholder="新敏感词（1–50 字符）" style="width:220px" @keyup.enter="add" />
      <el-button type="primary" @click="add">添加</el-button>
      <el-input v-model="keyword" placeholder="搜索词" clearable style="width:180px;margin-left:auto"
                @keyup.enter="() => { pageNum = 1; load() }" @clear="() => { pageNum = 1; load() }" />
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="word" label="敏感词" min-width="200" />
      <el-table-column label="添加时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 20" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="20" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
h2 { margin: 0 0 14px; font-size: 19px; }
.bar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 14px; justify-content: center; }
</style>
