<script setup>
/** 公告管理（M10）：草稿→发布→下架状态机 + 置顶 + 编辑对话框（生效时间窗）。 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminListAnnouncements, createAnnouncement, updateAnnouncement,
  transitionAnnouncement, toggleAnnouncementTop, deleteAnnouncement
} from '@/api/common'
import { fmtTime, statusTag, safeSrc } from '@/utils/format'
import { uploadImages } from '@/api/common'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const tab = ref('all')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 10 }
    if (tab.value !== 'all') params.status = Number(tab.value)
    const p = await adminListAnnouncements(params)
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
function switchTab() { pageNum.value = 1; load() }
onMounted(load)

const dlg = ref(false)
const editing = ref(null)
const form = reactive({ title: '', content: '', effectiveAt: '', expireAt: '', imageUrls: [] })

function openCreate() {
  editing.value = null
  Object.assign(form, { title: '', content: '', effectiveAt: '', expireAt: '', imageUrls: [] })
  pending.value.forEach((x) => URL.revokeObjectURL(x.url)); pending.value = []
  dlg.value = true
}
function openEdit(a) {
  editing.value = a
  Object.assign(form, {
    title: a.title, content: a.content, imageUrls: a.images || [],
    effectiveAt: a.effectiveAt ? String(a.effectiveAt).replace('T', ' ').slice(0, 19) : '',
    expireAt: a.expireAt ? String(a.expireAt).replace('T', ' ').slice(0, 19) : ''
  })
  dlg.value = true
}
async function save() {
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('标题和内容必填')
  if (pending.value.length) {
    const r = await uploadImages(pending.value.map((x) => x.file), 'announcements').catch(() => null)
    if (!r) return
    form.imageUrls.push(...(r.urls || []))
    pending.value.forEach((x) => URL.revokeObjectURL(x.url)); pending.value = []
  }
  const body = {
    title: form.title, content: form.content, imageUrls: form.imageUrls,
    effectiveAt: form.effectiveAt ? form.effectiveAt.replace(' ', 'T') : undefined,
    expireAt: form.expireAt ? form.expireAt.replace(' ', 'T') : undefined
  }
  try {
    if (editing.value) await updateAnnouncement(editing.value.id, { ...body, version: editing.value.version })
    else await createAnnouncement(body)
    ElMessage.success('已保存')
    dlg.value = false
    load()
  } catch { /* 2004 等 toast */ }
}

async function act(fn, msg) { try { await fn(); ElMessage.success(msg); load() } catch { /* toast */ } }

// 公告配图：两段式（选图存内存，保存草稿才上传），与物品/论坛口径一致
const pending = ref([])
function pickImages(e) {
  const picked = Array.from(e.target.files || [])
  e.target.value = ''
  if (!picked.length) return
  if (form.imageUrls.length + pending.length + picked.length > 6) return ElMessage.warning('最多 6 张')
  for (const f of picked) {
    if (!/^image\//.test(f.type)) { ElMessage.warning(`${f.name} 不是图片`); continue }
    if (f.size > 5 * 1024 * 1024) { ElMessage.warning(`${f.name} 超过 5MB`); continue }
    pending.value.push({ file: f, url: URL.createObjectURL(f) })
  }
}
function dropPending(i) { URL.revokeObjectURL(pending.value[i].url); pending.value.splice(i, 1) }
const publish = (a) => act(() => transitionAnnouncement(a.id, 1), '已发布')
const offline = (a) => act(() => transitionAnnouncement(a.id, 2), '已下架')
const top = (a) => act(() => toggleAnnouncementTop(a.id), a.topFlag === 1 ? '已取消置顶' : '已置顶')
async function remove(a) {
  try {
    await ElMessageBox.confirm(`删除公告「${a.title}」？（连带清图，不可恢复）`, '删除', { type: 'warning' })
    await act(() => deleteAnnouncement(a.id), '已删除')
  } catch { /* 取消 */ }
}
const st = (s) => statusTag(s, 'ANNOUNCE')
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <h2>公告管理</h2>
      <el-button type="primary" @click="openCreate">新建公告</el-button>
    </div>
    <el-tabs v-model="tab" @tab-change="switchTab">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="草稿" name="0" />
      <el-tab-pane label="已发布" name="1" />
      <el-tab-pane label="已下架" name="2" />
    </el-tabs>

    <el-table :data="rows" size="small">
      <el-table-column label="标题" min-width="220">
        <template #default="{ row }">
          <el-tag v-if="row.topFlag === 1" size="small" type="danger" effect="dark" class="tp">置顶</el-tag>
          {{ row.title }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"><el-tag :type="st(row.status).type" size="small">{{ st(row.status).text }}</el-tag></template>
      </el-table-column>
      <el-table-column label="生效窗口" width="230">
        <template #default="{ row }">
          {{ row.effectiveAt ? fmtTime(row.effectiveAt) : '即刻' }} → {{ row.expireAt ? fmtTime(row.expireAt) : '长期' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="270">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" size="small" type="success" @click="publish(row)">发布</el-button>
          <el-button v-if="row.status === 1" size="small" type="warning" @click="offline(row)">下架</el-button>
          <el-button v-if="row.status === 2" size="small" type="success" plain @click="publish(row)">再发布</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="top(row)">{{ row.topFlag === 1 ? '取消置顶' : '置顶' }}</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="10" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />

    <el-dialog v-model="dlg" :title="editing ? '编辑公告' : '新建公告'" width="560px">
      <el-form label-width="86px">
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="100" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="6" maxlength="5000" /></el-form-item>
        <el-form-item label="生效时间"><el-date-picker v-model="form.effectiveAt" type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss" placeholder="留空=即刻" style="width:100%" /></el-form-item>
        <el-form-item label="失效时间"><el-date-picker v-model="form.expireAt" type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss" placeholder="留空=长期" style="width:100%" /></el-form-item>
        <el-form-item label="配图">
          <div class="imgs">
            <div v-for="(u, i) in form.imageUrls" :key="u" class="thumb">
              <img :src="safeSrc(u)" alt="" />
              <button class="del" @click.prevent="form.imageUrls.splice(i, 1)">×</button>
            </div>
            <div v-for="(x, i) in pending" :key="x.url" class="thumb">
              <img :src="x.url" alt="" />
              <button class="del" @click.prevent="dropPending(i)">×</button>
            </div>
            <label v-if="form.imageUrls.length + pending.length < 6" class="up">
              <input type="file" accept="image/*" multiple hidden @change="pickImages" />
              <span>＋</span>
            </label>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; }
h2 { margin: 0 0 8px; font-size: 19px; }
.tp { margin-right: 6px; }
.pager { margin-top: 14px; justify-content: center; }
.imgs { display: flex; gap: 8px; flex-wrap: wrap; }
.thumb { position: relative; width: 76px; height: 76px; }
.thumb img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; border: 1px solid #ebeef5; }
.del { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; border-radius: 50%; border: none; background: #f56c6c; color: #fff; cursor: pointer; }
.up { width: 76px; height: 76px; border: 1px dashed #c0c4cc; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: #909399; font-size: 24px; }
.up:hover { border-color: #409eff; color: #409eff; }
</style>
