<script setup>
/** 用户治理（M10）：搜索 + 三态处置（封禁即踢线/禁言不禁读）+ 角色整组覆盖。 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminListUsers, adminSetUserStatus, adminAssignRoles } from '@/api/admin'
import { fmtTime } from '@/utils/format'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const keyword = ref('')
const statusFilter = ref(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 15 }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (statusFilter.value !== null && statusFilter.value !== '') params.status = statusFilter.value
    const p = await adminListUsers(params)
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

const STATUS = {
  1: { text: '正常', type: 'success' },
  0: { text: '已封禁', type: 'danger' },
  2: { text: '禁言中', type: 'warning' }
}
async function setStatus(u, s, label) {
  try {
    await ElMessageBox.confirm(`${label}「${u.nickname}（${u.studentNo}）」？${s === 0 ? '封禁将立即作废其全部登录。' : ''}`, label, { type: 'warning' })
    await adminSetUserStatus(u.id, s)
    ElMessage.success('已处置')
    load()
  } catch { /* 取消/toast */ }
}

const dlg = ref(false)
const cur = ref(null)
const selRoles = ref([])
const ROLE_OPTS = [
  { id: 1, name: '学生 ROLE_STUDENT' },
  { id: 2, name: '失物中心 ROLE_MANAGER' },
  { id: 3, name: '系统管理员 ROLE_ADMIN' }
]
function openRoles(u) {
  cur.value = u
  // 整组覆盖语义：当前 roles 名字反查 id 预勾
  selRoles.value = ROLE_OPTS.filter((r) => (u.roles || []).includes(r.name.split(' ')[1])).map((r) => r.id)
  dlg.value = true
}
async function saveRoles() {
  if (!selRoles.value.length) return ElMessage.warning('至少选一个角色')
  try {
    await adminAssignRoles(cur.value.id, selRoles.value)
    ElMessage.success('角色已更新（该用户需重新登录以刷新权限视图）')
    dlg.value = false
    load()
  } catch { /* 自剥 ADMIN 等后端护栏 toast */ }
}
</script>

<template>
  <div v-loading="loading">
    <h2>用户治理</h2>
    <div class="bar">
      <el-input v-model="keyword" placeholder="学号/昵称/邮箱" clearable style="width:220px"
                @keyup.enter="() => { pageNum = 1; load() }" @clear="() => { pageNum = 1; load() }" />
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width:140px" @change="() => { pageNum = 1; load() }">
        <el-option label="正常" :value="1" /><el-option label="已封禁" :value="0" /><el-option label="禁言中" :value="2" />
      </el-select>
      <el-button type="primary" @click="() => { pageNum = 1; load() }">搜索</el-button>
    </div>
    <el-table :data="rows" size="small">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="studentNo" label="学号" width="130" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="r in row.roles || []" :key="r" size="small" class="rt">{{ r.replace('ROLE_', '') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="(STATUS[row.status] || {}).type" size="small">{{ (STATUS[row.status] || {}).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="150">
        <template #default="{ row }">{{ row.lastLoginAt ? fmtTime(row.lastLoginAt) : '—' }}</template>
      </el-table-column>
      <el-table-column label="处置" width="250">
        <template #default="{ row }">
          <el-button v-if="row.status !== 1" size="small" type="success" plain @click="setStatus(row, 1, '恢复正常')">恢复</el-button>
          <el-button v-if="row.status !== 2" size="small" type="warning" plain @click="setStatus(row, 2, '禁言')">禁言</el-button>
          <el-button v-if="row.status !== 0" size="small" type="danger" plain @click="setStatus(row, 0, '封禁')">封禁</el-button>
          <el-button size="small" @click="openRoles(row)">角色</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 15" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="15" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />

    <el-dialog v-model="dlg" :title="`分配角色：${cur?.nickname || ''}`" width="420px">
      <el-alert type="info" :closable="false" class="tip" title="整组覆盖语义；保存后该用户全部登录令牌立即作废（权限视图随令牌快照）。" />
      <el-checkbox-group v-model="selRoles">
        <el-checkbox v-for="r in ROLE_OPTS" :key="r.id" :value="r.id" class="ro">{{ r.name }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
h2 { margin: 0 0 14px; font-size: 19px; }
.bar { display: flex; gap: 8px; margin-bottom: 12px; }
.rt { margin-right: 4px; }
.pager { margin-top: 14px; justify-content: center; }
.tip { margin-bottom: 12px; }
.ro { display: block; }
</style>
