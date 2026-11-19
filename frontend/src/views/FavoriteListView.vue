<script setup>
/** 我的收藏（M10）：ITEM/POST 两个 tab，targetId 回查详情渲染。 */
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { listFavorites, toggleFavorite } from '@/api/common'
import { fetchItem } from '@/api/item'
import { fetchPost } from '@/api/forum'
import { fmtTime } from '@/utils/format'

const router = useRouter()
const tab = ref('ITEM')
const raw = ref([])
const resolved = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    raw.value = await listFavorites(tab.value)
    const out = []
    for (const f of raw.value) {
      try {
        // pv 免计：收藏页"解析目标"不是浏览行为（用户口径：一人一次浏览）
        const vo = tab.value === 'ITEM' ? await fetchItem(f.targetId, { countView: false }) : await fetchPost(f.targetId, { countView: false })
        out.push({ ...f, vo })
      } catch { /* 目标已删/不可见：跳过渲染（数据保留，取消收藏仍可点） */ }
    }
    resolved.value = out
  } catch { resolved.value = [] } finally { loading.value = false }
}
onMounted(load)

async function unfav(f) {
  try {
    await toggleFavorite(f.targetType, f.targetId)
    await load()
  } catch { /* toast */ }
}
const go = (f) => router.push(f.targetType === 'ITEM' ? `/items/${f.targetId}` : `/forum/${f.targetId}`)
const isEmpty = computed(() => !loading.value && !resolved.value.length)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="收藏的物品" name="ITEM" />
      <el-tab-pane label="收藏的帖子" name="POST" />
    </el-tabs>
    <div class="list">
      <div v-for="f in resolved" :key="f.id" class="row">
        <el-link type="primary" @click="go(f)">{{ f.vo.title }}</el-link>
        <span class="dim">{{ fmtTime(f.createdAt) }}</span>
        <el-button size="small" link type="danger" @click="unfav(f)">取消收藏</el-button>
      </div>
      <el-empty v-if="isEmpty" description="暂无收藏" />
    </div>
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.row { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 12px 14px; display: flex; align-items: center; gap: 12px; }
.dim { color: #909399; font-size: 12px; margin-left: auto; }
</style>
