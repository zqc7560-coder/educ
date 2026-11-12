<script setup>
/** 我的发布（M10）：全状态对自己透明 + 状态筛选 tabs。 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyItems, changeItemStatus, deleteItem } from '@/api/item'
import ItemCard from '@/components/ItemCard.vue'

const router = useRouter()
const tab = ref('all')
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 12 }
    if (tab.value !== 'all') params.status = Number(tab.value)
    const p = await listMyItems(params)
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
function switchTab() { pageNum.value = 1; load() }
onMounted(load)

async function remove(it) {
  try {
    await ElMessageBox.confirm(`删除「${it.title}」？删除后进回收站。`, '删除', { type: 'warning' })
    await deleteItem(it.id)
    ElMessage.success('已删除')
    load()
  } catch { /* 取消/拦截器 toast */ }
}
</script>

<template>
  <div class="page" v-loading="loading">
    <el-tabs v-model="tab" @tab-change="switchTab">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="待审核" name="0" />
      <el-tab-pane label="已发布" name="1" />
      <el-tab-pane label="已下架" name="2" />
    </el-tabs>
    <div class="grid">
      <div v-for="it in rows" :key="it.id" class="cell">
        <ItemCard :item="it" show-status />
        <div class="ops">
          <el-button size="small" @click="router.push(`/user/items/${it.id}/edit`)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="remove(it)">删除</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !rows.length" description="还没有发布过信息" class="empty" />
    </div>
    <el-pagination v-if="total > 12" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="12" :current-page="pageNum"
                   @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; margin-top: 8px; min-height: 160px; }
.cell { display: flex; flex-direction: column; gap: 6px; }
.ops { display: flex; gap: 6px; justify-content: flex-end; }
.empty { grid-column: 1 / -1; }
.pager { margin-top: 16px; justify-content: center; }
</style>
