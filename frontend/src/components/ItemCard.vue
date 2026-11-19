<script setup>
/**
 * 物品卡片（M10 共享件）：大厅/我的发布/收藏/回收站四处复用。
 * 字段按视图裁剪（VO 装配已分场景），点击整卡进详情；status≠1 时由调用方
 * 通过 showStatus 打开状态角标（作者视角/治理视角才需要）。
 * 封面图（用户要求"按图找物"）：列表接口带 coverUrl（sort_order 首图），
 * contain 完整展示不裁切——横图竖图方图都全须全尾，白底居中自适应缩放；
 * 无图件不占位（v-if），不出现裂图。
 */
import { computed } from 'vue'
import { fmtTime, statusTag } from '@/utils/format'

const props = defineProps({
  item: { type: Object, required: true },
  showStatus: { type: Boolean, default: false }
})
const typeLabel = computed(() => (props.item.type === 'FOUND' ? '招领' : '失物'))
const typeColor = computed(() => (props.item.type === 'FOUND' ? 'success' : 'danger'))
const st = computed(() => statusTag(props.item.status))
const cover = computed(() => props.item.coverUrl || (props.item.images && props.item.images[0]) || '')
</script>

<template>
  <div class="card">
    <div class="head">
      <el-tag v-if="item.topFlag === 1" size="small" type="danger" effect="dark">置顶</el-tag>
      <el-tag :type="typeColor" size="small" effect="dark">{{ typeLabel }}</el-tag>
      <el-tag v-if="showStatus" :type="st.type" size="small">{{ st.text }}</el-tag>
      <span class="cat">{{ item.categoryName || '未分类' }}</span>
    </div>
    <router-link v-if="cover" :to="`/items/${item.id}`" class="cover-box" tabindex="-1">
      <img :src="cover" alt="" class="cover-img" loading="lazy" />
    </router-link>
    <router-link :to="`/items/${item.id}`" class="title">{{ item.title }}</router-link>
    <div class="desc">{{ item.description }}</div>
    <div class="meta">
      <span>{{ item.userNickname || '匿名' }}</span>
      <span>{{ fmtTime(item.publishedAt || item.createdAt) }}</span>
      <span>浏览 {{ item.viewCount ?? 0 }} · 申请 {{ item.claimCount ?? 0 }}</span>
    </div>
  </div>
</template>

<style scoped>
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; display: flex; flex-direction: column; gap: 8px; transition: box-shadow .15s; }
.card:hover { box-shadow: 0 2px 12px rgba(0,0,0,.08); }
.head { display: flex; align-items: center; gap: 8px; }
.cat { color: #909399; font-size: 12px; margin-left: auto; }
/* 封面：定高框 + contain——任何宽高比的图片都完整显示不裁切，白底居中 */
.cover-box { display: block; height: 150px; border-radius: 6px; overflow: hidden; background: #f7f8fa; border: 1px solid #f0f2f5; text-decoration: none; }
.cover-img { width: 100%; height: 100%; object-fit: contain; display: block; }
.title { color: #303133; font-weight: 600; text-decoration: none; font-size: 15px; line-height: 1.4; }
.title:hover { color: #409eff; }
.desc { color: #606266; font-size: 13px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.meta { display: flex; gap: 14px; color: #909399; font-size: 12px; }
</style>
