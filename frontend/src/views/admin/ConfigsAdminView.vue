<script setup>
/** 系统参数（M10）：白名单键动态表单（BOOL 开关/NUMBER/STRING）+ 清缓存应急口。 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listConfigs, updateConfig, clearCache } from '@/api/admin'

const rows = ref([])
const drafts = ref({})   // key → 编辑中的值
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await listConfigs()
    drafts.value = Object.fromEntries(rows.value.map((r) => [r.key, r.value]))
  } finally { loading.value = false }
}
onMounted(load)

async function save(r) {
  const v = String(drafts.value[r.key] ?? '').trim()
  if (v === r.value) return ElMessage.info('值未变化')
  try {
    await updateConfig(r.key, v)
    r.value = v
    ElMessage.success(`已更新 ${r.key}`)
  } catch { /* 类型闸 400 toast；失败回弹原值 */ drafts.value[r.key] = r.value }
}

async function doClearCache() {
  try {
    await ElMessageBox.confirm('清空物品详情/列表全部缓存键（下次读回源重建）。缓存异常兜底用，正常运营无需点。', '应急清缓存', { type: 'warning' })
    const r = await clearCache()
    ElMessage.success(`已清空 ${r.cleared} 个键`)
  } catch { /* 取消/toast */ }
}
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <h2>系统参数</h2>
      <el-button type="danger" plain size="small" @click="doClearCache">应急清缓存</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="key" label="参数键" width="220" />
      <el-table-column prop="description" label="说明" min-width="240" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.type }}</el-tag></template>
      </el-table-column>
      <el-table-column label="值" width="260">
        <template #default="{ row }">
          <el-switch v-if="row.type === 'BOOL'" :model-value="row.value === '1'"
                     :disabled="!row.editable"
                     @update:model-value="(x) => { drafts[row.key] = x ? '1' : '0'; save(row) }" />
          <el-input v-else v-model="drafts[row.key]" :disabled="!row.editable" size="small"
                    @keyup.enter="save(row)">
            <template v-if="row.editable" #append>
              <el-button @click="save(row)">存</el-button>
            </template>
          </el-input>
        </template>
      </el-table-column>
      <el-table-column label="可改" width="70">
        <template #default="{ row }">{{ row.editable ? '✓' : '锁定' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; }
h2 { margin: 0 0 14px; font-size: 19px; }
</style>
