<script setup>
/**
 * 失物大厅/首页（M10 实体版）：筛选（类型/分类/关键词/排序）+ 分页列表。
 *
 * 数据纪律：列表请求带条件即重建（不叠分页游标）；翻页保持条件；失败保留旧数据
 * 只 toast（拦截器统一弹）——空态用 el-empty 明示而非白屏。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listItems, fetchCategories } from '@/api/item'
import ItemCard from '@/components/ItemCard.vue'
import { useUserStore } from '@/stores/user'
import { safeSrc } from '@/utils/format'

const router = useRouter()
const store = useUserStore()
const isGuest = computed(() => !store.isLoggedIn)

const q = reactive({ type: '', categoryId: null, keyword: '', sort: 'new', pageNum: 1, pageSize: 12 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const cats = ref([])

async function load() {
  loading.value = true
  try {
    const params = { pageNum: q.pageNum, pageSize: q.pageSize, sort: q.sort }
    if (q.type) params.type = q.type
    if (q.categoryId) params.categoryId = q.categoryId
    if (q.keyword) params.keyword = q.keyword
    const page = await listItems(params)
    rows.value = page.list || []
    total.value = page.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
function search() { q.pageNum = 1; load() }

onMounted(async () => {
  load()
  try { cats.value = await fetchCategories('ITEM') } catch { cats.value = [] }
})
</script>

<template>
  <div class="wrap hall">
    <div class="filters">
      <el-radio-group v-model="q.type" @change="search">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="LOST">失物</el-radio-button>
        <el-radio-button value="FOUND">招领</el-radio-button>
      </el-radio-group>
      <el-select v-model="q.categoryId" placeholder="全部分类" clearable style="width:140px" @change="search">
        <el-option v-for="c in cats" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-input v-model="q.keyword" placeholder="搜标题/描述关键词" clearable style="width:240px"
                @keyup.enter="search" @clear="search" />
      <el-select v-model="q.sort" style="width:120px" @change="search">
        <el-option label="最新" value="new" />
        <el-option label="最热" value="hot" />
      </el-select>
      <el-button type="primary" @click="search">检索</el-button>
    </div>

    <!-- 游客引导条：大厅/论坛/公告对游客开放浏览，发布与认领需注册登录 -->
    <el-alert v-if="isGuest" class="guest-tip" type="info" :closable="false">
      <template #title>
        游客可浏览已发布的失物与招领信息；
        <el-link type="primary" @click="router.push('/login')">注册 / 登录</el-link>
        后即可发布信息、申请认领、进入论坛发帖。
      </template>
    </el-alert>

    <div v-loading="loading" class="grid">
      <ItemCard v-for="it in rows" :key="it.id" :item="it" />
      <el-empty v-if="!loading && !rows.length" description="没有符合条件的物品" class="empty" />
    </div>

    <el-pagination v-if="total > q.pageSize" class="pager" layout="prev, pager, next"
                   :total="total" :page-size="q.pageSize" :current-page="q.pageNum"
                   @current-change="(p) => { q.pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.wrap { max-width: 1080px; margin: 0 auto; }
.hall { padding: 20px 16px 40px; }
.guest-tip { margin-bottom: 14px; }
.filters { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; align-items: center; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; min-height: 200px; }
.empty { grid-column: 1 / -1; }
.pager { margin-top: 20px; justify-content: center; }
</style>
