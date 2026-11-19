<script setup>
/**
 * 失物大厅/首页（M10 实体版）：筛选（类型/分类/关键词/排序）+ 分页列表。
 *
 * 数据纪律：列表请求带条件即重建（不叠分页游标）；翻页保持条件；失败保留旧数据
 * 只 toast（拦截器统一弹）——空态用 el-empty 明示而非白屏。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listItems, fetchCategories, fetchCarousel } from '@/api/item'
import ItemCard from '@/components/ItemCard.vue'
import { useUserStore } from '@/stores/user'
import { safeSrc } from '@/utils/format'

const router = useRouter()
const store = useUserStore()
const isGuest = computed(() => !store.isLoggedIn)

const q = reactive({ type: '', categoryId: null, keyword: '', sort: 'new', pageNum: 1, pageSize: 12 })
const rows = ref([])
const total = ref(0)
const frames = ref([])   // 首页轮播帧：0/1 帧不显示轮播（1 张图无需轮）
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
  try { frames.value = await fetchCarousel() } catch { frames.value = [] }
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

    <!-- 首页图片轮播：每件已发布带图招领/失物一帧，看图认物直达详情；
         不足 2 帧自动退化为静态单图或隐藏（轮播只在多帧时有意义） -->
    <template v-if="frames.length >= 2">
      <el-carousel :interval="4000" class="carousel" arrow="hover" indicator-position="outside" height="260px">
        <el-carousel-item v-for="f in frames" :key="f.itemId">
          <router-link :to="`/items/${f.itemId}`" class="slide">
            <img :src="safeSrc(f.url)" alt="" class="slide-img" />
            <div class="slide-cap">
              <el-tag size="small" :type="f.type === 'FOUND' ? 'success' : 'danger'" effect="dark">{{ f.type === 'FOUND' ? '招领' : '失物' }}</el-tag>
              <span>{{ f.title }}</span>
            </div>
          </router-link>
        </el-carousel-item>
      </el-carousel>
    </template>
    <div v-else-if="frames.length === 1" class="carousel single">
      <router-link :to="`/items/${frames[0].itemId}`" class="slide">
        <img :src="safeSrc(frames[0].url)" alt="" class="slide-img" />
        <div class="slide-cap">
          <el-tag size="small" :type="frames[0].type === 'FOUND' ? 'success' : 'danger'" effect="dark">{{ frames[0].type === 'FOUND' ? '招领' : '失物' }}</el-tag>
          <span>{{ frames[0].title }}</span>
        </div>
      </router-link>
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
.carousel { margin-bottom: 16px; border-radius: 10px; overflow: hidden; background: #fff; border: 1px solid #ebeef5; }
.slide { display: block; height: 100%; position: relative; text-decoration: none; }
.slide-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.slide-cap { position: absolute; left: 0; right: 0; bottom: 0; padding: 10px 16px; display: flex; align-items: center; gap: 10px; color: #fff; background: linear-gradient(transparent, rgba(0,0,0,.55)); font-size: 14px; }
.carousel.single .slide-img { height: 260px; }
.guest-tip { margin-bottom: 14px; }
.filters { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; align-items: center; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; min-height: 200px; }
.empty { grid-column: 1 / -1; }
.pager { margin-top: 20px; justify-content: center; }
</style>
