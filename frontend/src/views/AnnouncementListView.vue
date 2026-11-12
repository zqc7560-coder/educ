<template>
  <div class="wrap page" v-loading="loading">
    <h2>学校公告</h2>
    <div class="list">
      <div v-for="a in rows" :key="a.id" class="item">
        <div class="head" @click="toggle(a)">
          <el-tag v-if="a.topFlag === 1" size="small" type="danger" effect="dark">置顶</el-tag>
          <b>{{ a.title }}</b>
          <span class="dim">{{ fmtTime(a.createdAt) }} · 阅读 {{ a.viewCount }}</span>
        </div>
        <div v-if="expandedId === a.id" class="body">
          <p>{{ (detail[a.id] || a).content }}</p>
          <div v-if="detail[a.id] && detail[a.id].images && detail[a.id].images.length" class="imgs">
            <el-image v-for="(u, i) in detail[a.id].images" :key="i" :src="safeSrc(u)" fit="cover" class="img"
                      :preview-src-list="detail[a.id].images.map(safeSrc).filter(Boolean)" :initial-index="i" preview-teleported />
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && !rows.length" description="暂无公告" />
    </div>
    <el-pagination v-if="total > 10" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="10" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<script setup>
/**
 * 公告列表（M10）：分页 + 点击展开展示全文与图片（详情接口带 images，
 * 展开时才拉详情=懒加载，列表接口不传大字段）。
 */
import { ref, onMounted } from 'vue'
import { listAnnouncements, fetchAnnouncement } from '@/api/common'
import { fmtTime, safeSrc } from '@/utils/format'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)
const expandedId = ref(null)
const detail = ref({})

async function load() {
  loading.value = true
  try {
    const p = await listAnnouncements({ pageNum: pageNum.value, pageSize: 10 })
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
onMounted(load)

async function toggle(a) {
  if (expandedId.value === a.id) { expandedId.value = null; return }
  expandedId.value = a.id
  if (!detail.value[a.id]) {
    try { detail.value[a.id] = await fetchAnnouncement(a.id) } catch { detail.value[a.id] = a }
  }
}
</script>

<style scoped>
.wrap { max-width: 800px; margin: 0 auto; }
.page { padding: 24px 16px 48px; }
h2 { margin: 0 0 16px; }
.list { display: flex; flex-direction: column; gap: 10px; }
.item { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; }
.head { display: flex; align-items: center; gap: 10px; padding: 14px 16px; cursor: pointer; }
.head b { color: #303133; }
.dim { margin-left: auto; color: #909399; font-size: 12px; }
.body { padding: 0 16px 14px; border-top: 1px dashed #f0f2f5; }
.body p { color: #606266; line-height: 1.8; white-space: pre-wrap; }
.imgs { display: flex; gap: 10px; flex-wrap: wrap; }
.img { width: 130px; height: 100px; border-radius: 6px; }
.pager { margin-top: 18px; justify-content: center; }
</style>
