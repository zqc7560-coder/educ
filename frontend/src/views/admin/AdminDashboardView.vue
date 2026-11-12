<script setup>
/** 数据看板（M8 接口的前端半边）：六卡+找回率+7 日趋势(手绘柱状)+分类分布+热帖+待办角标。 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { statsOverview } from '@/api/admin'

const router = useRouter()
const d = ref(null)
const loading = ref(true)

onMounted(async () => {
  try { d.value = await statsOverview() } finally { loading.value = false }
})

const cards = computed(() => d.value ? [
  { label: '注册用户', value: d.value.userTotal },
  { label: '物品总数', value: d.value.itemTotal },
  { label: '失物 / 招领', value: `${d.value.lostCount} / ${d.value.foundCount}` },
  { label: '认领单', value: d.value.claimTotal },
  { label: '论坛帖子', value: d.value.postTotal },
  { label: '找回率', value: `${((d.value.returnRate || 0) * 100).toFixed(1)}%`, hot: true }
] : [])
const trend = computed(() => d.value?.trend7d || [])
const maxTrend = computed(() => Math.max(1, ...trend.value.map((x) => x.items + x.posts)))
const pending = computed(() => d.value ? [
  { label: '待审物品', n: d.value.pendingItems, to: '/admin/audit' },
  { label: '待审帖子', n: d.value.pendingPosts, to: '/admin/audit' },
  { label: '待审回复', n: d.value.pendingReplies, to: '/admin/audit' }
] : [])
const maxCat = computed(() => Math.max(1, ...(d.value?.categoryDist || []).map((c) => c.count)))
</script>

<template>
  <div v-loading="loading">
    <h2>数据看板</h2>
    <div class="cards">
      <div v-for="c in cards" :key="c.label" class="card">
        <div class="v" :class="{ hot: c.hot }">{{ c.value }}</div>
        <div class="l">{{ c.label }}</div>
      </div>
    </div>

    <div class="row">
      <div class="panel">
        <h3>待办</h3>
        <div v-for="p in pending" :key="p.label" class="pend" @click="router.push(p.to)">
          <span>{{ p.label }}</span><b :class="{ warn: p.n > 0 }">{{ p.n }}</b>
        </div>
      </div>
      <div class="panel">
        <h3>近 7 日发布趋势（上=物品 下=帖子）</h3>
        <div class="trend">
          <div v-for="t in trend" :key="t.date" class="tcol">
            <div class="bars">
              <div class="bar items" :style="{ height: (t.items / maxTrend) * 90 + 'px' }" :title="`物品 ${t.items}`"></div>
              <div class="bar posts" :style="{ height: (t.posts / maxTrend) * 90 + 'px' }" :title="`帖子 ${t.posts}`"></div>
            </div>
            <div class="day">{{ t.date.slice(5) }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="row">
      <div class="panel">
        <h3>分类分布</h3>
        <div v-for="c in d?.categoryDist || []" :key="c.name" class="dist">
          <span class="name">{{ c.name }}</span>
          <div class="track"><div class="fill" :style="{ width: (c.count / maxCat) * 100 + '%' }"></div></div>
          <b>{{ c.count }}</b>
        </div>
      </div>
      <div class="panel">
        <h3>论坛热度 Top5</h3>
        <div v-for="(p, i) in d?.hotPosts || []" :key="p.id" class="hot" @click="router.push(`/forum/${p.id}`)">
          <b class="rk">{{ i + 1 }}</b><span class="t">{{ p.title }}</span>
          <span class="dim">回复 {{ p.replyCount }} · 赞 {{ p.likeCount }}</span>
        </div>
        <el-empty v-if="!(d?.hotPosts || []).length" :image-size="50" description="暂无热帖" />
      </div>
    </div>
  </div>
</template>

<style scoped>
h2 { margin: 0 0 16px; font-size: 19px; }
.cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 16px; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px; text-align: center; }
.v { font-size: 22px; font-weight: 700; color: #303133; }
.v.hot { color: #67c23a; }
.l { color: #909399; font-size: 13px; margin-top: 6px; }
.row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px 18px; }
.panel h3 { margin: 0 0 12px; font-size: 15px; }
.pend { display: flex; justify-content: space-between; padding: 8px 4px; border-bottom: 1px dashed #f0f2f5; cursor: pointer; }
.pend:hover { color: #409eff; }
.pend .warn { color: #e6a23c; }
.trend { display: flex; align-items: flex-end; gap: 10px; height: 120px; }
.tcol { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; }
.bars { display: flex; align-items: flex-end; gap: 3px; height: 100px; }
.bar { width: 12px; border-radius: 3px 3px 0 0; }
.bar.items { background: #409eff; }
.bar.posts { background: #67c23a; }
.day { color: #909399; font-size: 11px; }
.dist { display: flex; align-items: center; gap: 10px; padding: 5px 0; font-size: 13px; }
.dist .name { width: 78px; color: #606266; }
.track { flex: 1; height: 10px; background: #f0f2f5; border-radius: 5px; overflow: hidden; }
.fill { height: 100%; background: #409eff; border-radius: 5px; }
.hot { display: flex; align-items: center; gap: 10px; padding: 7px 4px; border-bottom: 1px dashed #f0f2f5; cursor: pointer; }
.rk { color: #e6a23c; width: 16px; }
.hot .t { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #303133; }
.dim { color: #909399; font-size: 12px; }
</style>
