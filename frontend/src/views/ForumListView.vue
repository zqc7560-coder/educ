<script setup>
/** 校园论坛列表（M10）：板块 tab + 关键词 + 分页；发帖入口登录可见。 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listPosts } from '@/api/forum'
import { fetchCategories } from '@/api/item'
import { fmtTime, relTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const boards = ref([])
const boardId = ref(route.query.board ? Number(route.query.board) : 0)  // 0=全部板块哨兵（el-tabs name 不吃 null）
const keyword = ref('')
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 15 }
    if (boardId.value) params.boardId = boardId.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const p = await listPosts(params)
    rows.value = p.list || []
    total.value = p.total || 0
  } catch { rows.value = [] } finally { loading.value = false }
}
function search() { pageNum.value = 1; load() }

onMounted(async () => {
  load()
  boards.value = await fetchCategories('BOARD').catch(() => [])
})

const newPost = () => {
  if (!store.isLoggedIn) return router.push({ path: '/login', query: { redirect: '/forum/new' } })
  router.push('/forum/new')
}
</script>

<template>
  <div class="wrap page">
    <div class="bar">
      <el-tabs v-model="boardId" class="tabs" @tab-change="search">
        <el-tab-pane label="全部板块" :name="0" />
        <el-tab-pane v-for="b in boards" :key="b.id" :label="b.name" :name="b.id" />
      </el-tabs>
      <div class="right">
        <el-input v-model="keyword" placeholder="搜标题/正文" clearable style="width:200px"
                  @keyup.enter="search" @clear="search" />
        <el-button type="primary" @click="newPost">发帖</el-button>
      </div>
    </div>

    <div v-loading="loading" class="list">
      <div v-for="p in rows" :key="p.id" class="row">
        <div class="l">
          <div class="l1">
            <el-tag v-if="p.topFlag === 1" size="small" type="danger" effect="dark">置顶</el-tag>
            <el-tag v-else-if="p.status !== 1" size="small" type="warning">待审核</el-tag>
            <router-link :to="`/forum/${p.id}`" class="title">{{ p.title }}</router-link>
            <span class="board">{{ p.boardName }}</span>
          </div>
          <div class="l2">
            <span>{{ p.nickname }}</span>
            <span>{{ relTime(p.publishedAt || p.createdAt) }}</span>
          </div>
        </div>
        <div class="stat">
          <div>赞 {{ p.likeCount }}</div>
          <div>回复 {{ p.replyCount }}</div>
          <div>看 {{ p.viewCount }}</div>
        </div>
      </div>
      <el-empty v-if="!loading && !rows.length" description="这里还很安静，来发第一帖？" />
    </div>

    <el-pagination v-if="total > 15" class="pager" layout="prev, pager, next" :total="total"
                   :page-size="15" :current-page="pageNum" @current-change="(p) => { pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.wrap { max-width: 920px; margin: 0 auto; }
.page { padding: 20px 16px 40px; }
.bar { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
.tabs { flex: 1; }
.right { display: flex; gap: 8px; }
.list { display: flex; flex-direction: column; gap: 10px; margin-top: 6px; min-height: 200px; }
.row { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; display: flex; gap: 16px; }
.row:hover { border-color: #c6e2ff; }
.l { flex: 1; min-width: 0; }
.l1 { display: flex; align-items: center; gap: 8px; }
.title { color: #303133; font-weight: 600; text-decoration: none; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.title:hover { color: #409eff; }
.board { margin-left: auto; color: #909399; font-size: 12px; }
.l2 { display: flex; gap: 14px; color: #909399; font-size: 12px; margin-top: 6px; }
.stat { display: flex; flex-direction: column; gap: 2px; color: #606266; font-size: 12px; min-width: 62px; text-align: right; }
.pager { margin-top: 18px; justify-content: center; }
</style>
